package net.plan99.gumball

import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.SystemExitException
import com.xenomachina.argparser.default
import com.xenomachina.argparser.mainBody
import java.io.BufferedOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions

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
        val creator = BootJarCreator(classpath, tmpdir, mainClassName, platform, eliminateDeadCode, lzmaCompress)
        // Generate the ELF file to link.
        creator.bootjarAsELF.close()
        // Generate the bootstrap object file.
        generateBootstrap()
        // Link them all together.
        linkObjects()
        return Files.newInputStream(tmpdir / "app")
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
    val saveTemps by parser.flagging("--save-temps", help = "Keep the working directory that contains temporary files").default(false)
    // LZMA defaults to false as the startup time hit doesn't feel worth it.
    val lzma by parser.flagging("Compress the bytecode using LZMA. Results in slower startup but slightly smaller binaries.").default(false)
    val mainClass by parser.positional("MAIN", help = "Name of the class containing the app's main method")
    val output by parser.positional("OUTPUT", help = "Path to the output file") { asPath }.addValidator {
        if (Files.isDirectory(value))
            throw SystemExitException("The output path must be a file name, not a directory", 1)
    }
}

fun main(args: Array<String>) {
    mainBody("gumball") {
        Args(ArgParser(args)).run {
            val tmpdir = Files.createTempDirectory("gumball")
            try {
                GumballMachine(classpath, mainClass, tmpdir, Platform.MAC64, true, lzma).make().copyTo(output)
                Files.setPosixFilePermissions(output, PosixFilePermissions.fromString("rwxr-x---"))
            } catch(e: UnsupportedOperationException) {
                // Not on UNIX
            } catch(e: SubprocessFailed) {
                println(e.message)
            } finally {
                if (saveTemps) {
                    println("Temporary working files can be found in $tmpdir")
                } else {
                    tmpdir.toFile().deleteRecursively()
                }
            }
        }
    }
}