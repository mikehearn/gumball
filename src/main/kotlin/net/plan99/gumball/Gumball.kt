@file:JvmName("Gumball")
package net.plan99.gumball

import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.SystemExitException
import com.xenomachina.argparser.default
import com.xenomachina.argparser.mainBody
import proguard.Configuration
import proguard.ConfigurationParser
import proguard.ProGuard
import java.io.*
import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.util.jar.JarInputStream
import java.util.jar.JarOutputStream
import java.util.logging.Logger
import java.util.zip.ZipEntry
import kotlin.system.measureTimeMillis

enum class Platform {
    MAC64;

    fun extractFile(to: Path, name: String): Path {
        val path = to / name
        stream(name).copyTo(path)
        return path
    }

    fun stream(file: String): InputStream = javaClass.getResourceAsStream(name.toLowerCase() + "/$file")
}

class GumballMachine(val classpath: List<Path>,
                     val mainClassName: String,
                     val tmpdir: Path,
                     val platform: Platform,
                     val eliminateDeadCode: Boolean,
                     val lzmaCompress: Boolean) {
    private val mainClassNameLenLimit = 256
    private val log = Logger.getLogger(GumballMachine::class.java.name)

    init {
        require(mainClassName.length < mainClassNameLenLimit - 2) {
            "Main class name is too long, cannot be more than ${mainClassNameLenLimit - 2} characters"
        }
        Files.createDirectories(tmpdir)
        tmpdir.toFile().deleteOnExit()
    }

    fun make(): InputStream {
        // Create the unified JAR that contains the standard library and app classes together.
        // In future we should separate app and boot jar for better compatibility with existing code.
        step("Generating application JAR archive")
        val bootjar = ByteArrayOutputStream()
        createUberJar(classpath, listOf(JarInputStream(javaClass.getResourceAsStream("avian-stdlib.jar"))), bootjar)
        // Generate the ELF file to link.
        convertToELF(bootjar.toByteArray().inputStream())
        // Generate the bootstrap object file.
        step("Creating bootstrap code")
        generateBootstrap()
        // Link them all together.
        step("Linking executable")
        linkObjects()
        return Files.newInputStream(tmpdir / "app")
    }

    private fun step(s: String) {
        if (hasEmojiTerminal) print("$CODE_RIGHT_ARROW  ")
        println(s)
    }

    fun createUberJar(paths: List<Path>, additionalStreams: List<JarInputStream>, output: OutputStream) {
        val result = JarOutputStream(output)
        for (path in paths) {
            if (Files.isDirectory(path)) {
                Files.walk(path, FileVisitOption.FOLLOW_LINKS).forEachOrdered { file ->
                    if (Files.isRegularFile(file)) {
                        result.putNextEntry(ZipEntry(path.relativize(file).toString()))
                        Files.newInputStream(file).use { it.copyTo(result) }
                        result.closeEntry()
                    }
                }
            } else if (!Files.isReadable(path) || !path.toString().toLowerCase().endsWith(".jar")) {
                log.fine("Skipping $path as it is not a readable JAR file")
            } else {
                JarInputStream(Files.newInputStream(path)).use { input ->
                    while (true) {
                        val inEntry = input.nextJarEntry ?: break
                        if (!inEntry.isDirectory) {
                            result.putNextEntry(ZipEntry(inEntry.name))
                            input.copyTo(result)
                            result.closeEntry()
                        }
                    }
                }
            }
        }
        for (stream in additionalStreams) {
            stream.use { input ->
                while (true) {
                    val inEntry = input.nextJarEntry ?: break
                    if (!inEntry.isDirectory) {
                        result.putNextEntry(ZipEntry(inEntry.name))
                        input.copyTo(result)
                        result.closeEntry()
                    }
                }
            }
        }
        result.close()
    }

    private fun convertToELF(bootjar: InputStream) {
        // Create the boot JAR and optionally ProGuard it.
        val uberjarPath = tmpdir / "uber.jar"
        if (!eliminateDeadCode)
            bootjar.copyTo(uberjarPath)
        else
            createOptimisedBootJar(bootjar)

        val name = if (lzmaCompress) {
            val lzma = platform.extractFile(tmpdir, "lzma")
            val objName = "uber.jar.lzma"
            step("LZMA compressing code")
            run(tmpdir, lzma, "encode", "uber.jar", objName)
            objName
        } else {
            "uber.jar"
        }

        // Convert it to a linkable object file.
        step("Converting JAR to native object file")
        val outputPath = tmpdir / "uber.jar.o"
        binToObj(tmpdir / name, outputPath)
    }

    private fun binToObj(uberjarPath: Path, outputPath: Path) {
        // TODO: Port to other platforms.
        val binToObjPath = platform.extractFile(tmpdir, "bin-to-obj")
        run(tmpdir,
                binToObjPath.toAbsolutePath(),
                uberjarPath.toAbsolutePath(),
                outputPath.toAbsolutePath(),
                "_binary_boot_jar_start",
                "_binary_boot_jar_end",
                "macho",
                "x86_64")
        check(Files.exists(outputPath))
    }

    private fun createOptimisedBootJar(fromJar: InputStream) {
        step("Scanning for and eliminating dead code")
        fromJar.copyTo(tmpdir / "uber-preopt.jar")

        // Write out a ProGuard config for this program.
        val vmProGuard = javaClass.getResourceAsStream("vm.pro").bufferedReader().use { it.readText() }
        val openjdkProGuard = javaClass.getResourceAsStream("openjdk.pro").bufferedReader().use { it.readText() }
        val configStr = """
        -ignorewarnings
        -dontusemixedcaseclassnames
        -dontoptimize
        -dontobfuscate
        -injars uber-preopt.jar
        -outjars uber.jar
        -keep class $mainClassName { public static void main(java.lang.String[]); }

        """.trimIndent() + vmProGuard + openjdkProGuard

        Files.write(tmpdir / "proguard.conf", configStr.toByteArray())

        val configParser = ConfigurationParser(configStr, "inmemory-proguard-conf", tmpdir.toFile(), System.getProperties())
        val config = Configuration()
        configParser.parse(config)
        val pg = ProGuard(config)

        // Now run ProGuard but with stdout redirected to a file, as ProGuard doesn't use any kind of logging framework.
        val prevOutStream = System.out
        val prevErrStream = System.err
        try {
            System.setOut(PrintStream((tmpdir / "proguard-info.log").toString()))
            System.setErr(PrintStream((tmpdir / "proguard-warnings.log").toString()))
            pg.execute()
        } finally {
            System.setOut(prevOutStream)
            System.setErr(prevErrStream)
        }
    }

    private fun generateBootstrap() {
        // Null terminate the name of the main class and edit into the bootstrap binary, along with a tag byte
        // indicating use of LZMA.
        val injectedBytes = ((if (lzmaCompress) 'L' else ' ') + mainClassName).toByteArray().copyOf(mainClassName.length + 2)
        val editor = BinaryEditor('X'.toInt(), mainClassNameLenLimit, injectedBytes)
        val bootstrapObjPath = tmpdir / "bootstrap.o"
        platform.stream("bootstrap.o").use { input ->
            BufferedOutputStream(Files.newOutputStream(bootstrapObjPath)).use { output ->
                editor.edit(input, output)
            }
        }
    }

    private fun linkObjects() {
        val archive = platform.extractFile(tmpdir, "libavian.a")
        val cmd = "/usr/bin/g++ -rdynamic -Wl,-all_load $archive bootstrap.o uber.jar.o  -ldl -lpthread -lz -lobjc -o app -framework CoreFoundation -framework SystemConfiguration -framework Security -framework CoreServices -framework Cocoa"
        run(tmpdir, cmd)
        run(tmpdir, "/usr/bin/strip -S -x app")
    }
}

class Args(parser: ArgParser) {
    val classpath by parser.storing(help = "Colon separated list of JARs to compile") { split(":").map { Paths.get(it) } }
    val saveTemps by parser.flagging(names = "--save-temps", help = "Keep the working directory that contains temporary files").default(false)
    // LZMA defaults to false as the startup time hit doesn't feel worth it.
    val lzma by parser.flagging("Compress the bytecode using LZMA. Results in slower startup but slightly smaller binaries.").default(false)
    val mainClass by parser.positional("MAIN", help = "Name of the class containing the app's main method")
    val output by parser.positional("OUTPUT", help = "Path to the output file") { asPath }.addValidator {
        if (Files.isDirectory(value))
            throw SystemExitException("The output path must be a file name, not a directory", 1)
    }
    val noProguard by parser.flagging("--no-proguard", help = "Skip the ProGuard shrink step (huge binaries but much faster)").default(true)
}

fun main(args: Array<String>) {
    mainBody("gumball") {
        Args(ArgParser(args)).run {
            val tmpdir = Files.createTempDirectory("gumball")
            if (saveTemps)
                println("Temporary working files can be found in $tmpdir")
            try {
                val elapsed = measureTimeMillis {
                    GumballMachine(classpath, mainClass, tmpdir, Platform.MAC64, !noProguard, lzma).make().copyTo(output)
                }
                Files.setPosixFilePermissions(output, PosixFilePermissions.fromString("rwxr-x---"))
                println()
                println("Took ${(elapsed / 100.0).toInt() / 10.0} seconds")
            } catch(e: UnsupportedOperationException) {
                // Not on UNIX
            } catch(e: SubprocessFailed) {
                println(e.message)
            } finally {
                if (!saveTemps) {
                    tmpdir.toFile().deleteRecursively()
                }
            }
        }
    }
}