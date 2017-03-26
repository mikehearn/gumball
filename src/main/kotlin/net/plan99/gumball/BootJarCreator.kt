package net.plan99.gumball

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileVisitOption
import java.nio.file.Files.*
import java.nio.file.Path
import java.util.jar.JarInputStream
import java.util.jar.JarOutputStream
import java.util.logging.Logger
import java.util.zip.ZipEntry

class BootJarCreator(private val paths: List<Path>, private val tmpdir: Path) {
    private val log = Logger.getLogger(BootJarCreator::javaClass.name)

    class SubprocessFailed(name: String, retcode: Int) : Exception("Subprocess ${name} failed with error code $retcode")

    val bootjar: InputStream get() {
        val stdlib = JarInputStream(javaClass.getResourceAsStream("avian-stdlib.jar"))
        val result = ByteArrayOutputStream()
        createUberJar(paths, listOf(stdlib), result)
        return ByteArrayInputStream(result.toByteArray())
    }

    val bootjarAsELF: InputStream get() {
        val uberjarPath = tmpdir / "uber.jar"
        bootjar.copyTo(uberjarPath)
        val outputPath = (uberjarPath.toString() + ".o").asPath
        binToObj(uberjarPath, outputPath)
        return newInputStream(outputPath)
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
