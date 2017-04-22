package net.plan99.gumball

import proguard.Configuration
import proguard.ConfigurationParser
import proguard.ProGuard
import java.io.*
import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.Files.*
import java.nio.file.Path
import java.util.jar.JarInputStream
import java.util.jar.JarOutputStream
import java.util.logging.Logger
import java.util.zip.ZipEntry

class BootJarCreator(private val paths: List<Path>, private val tmpdir: Path,
                     private val mainClass: String,
                     private val eliminateDeadCode: Boolean,
                     private val lzmaCompress: Boolean) {
    private val log = Logger.getLogger(BootJarCreator::javaClass.name)

    val bootjar: InputStream get() {
        val stdlib = JarInputStream(javaClass.getResourceAsStream("avian-stdlib.jar"))
        val result = ByteArrayOutputStream()
        createUberJar(paths, listOf(stdlib), result)
        return ByteArrayInputStream(result.toByteArray())
    }

    val bootjarAsELF: InputStream get() {
        // Create the boot JAR and optionally ProGuard it.
        val uberjarPath = tmpdir / "uber.jar"
        if (!eliminateDeadCode)
            bootjar.copyTo(uberjarPath)
        else
            createOptimisedBootJar(bootjar)

        val name = if (lzmaCompress) {
            javaClass.getResourceAsStream("lzma-mac64").copyTo(tmpdir / "lzma")
            val objName = "uber.jar.lzma"
            run(tmpdir, tmpdir / "lzma", "encode", "uber.jar", objName)
            objName
        } else {
            "uber.jar"
        }

        // Convert it to a linkable object file.
        val outputPath = tmpdir / "uber.jar.o"
        binToObj(tmpdir / name, outputPath)
        return newInputStream(outputPath)
    }

    private fun createOptimisedBootJar(fromJar: InputStream) {
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
        -keep class $mainClass { public static void main(java.lang.String[]); }

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

    private fun binToObj(uberjarPath: Path, outputPath: Path) {
        val binToObjPath = tmpdir / "binaryToObject"
        // TODO: Port to other platforms.
        javaClass.getResourceAsStream("binaryToObject-mac64").copyTo(binToObjPath)
        run(tmpdir,
                binToObjPath.toAbsolutePath(),
                uberjarPath.toAbsolutePath(),
                outputPath.toAbsolutePath(),
                "_binary_boot_jar_start",
                "_binary_boot_jar_end",
                "macho",
                "x86_64")
        check(exists(outputPath))
    }

    private fun createUberJar(paths: List<Path>, additionalStreams: List<JarInputStream>, output: OutputStream) {
        val result = JarOutputStream(output)
        for (path in paths) {
            if (isDirectory(path)) {
                walk(path, FileVisitOption.FOLLOW_LINKS).forEachOrdered { file ->
                    if (isRegularFile(file)) {
                        result.putNextEntry(ZipEntry(path.relativize(file).toString()))
                        newInputStream(file).use { it.copyTo(result) }
                        result.closeEntry()
                    }
                }
            } else if (!isReadable(path) || !path.toString().toLowerCase().endsWith(".jar")) {
                log.warning("Skipping $path as it is not a readable JAR file")
            } else {
                JarInputStream(newInputStream(path)).use { input ->
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

}
