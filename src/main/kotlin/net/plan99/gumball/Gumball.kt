package net.plan99.gumball

import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.SystemExitException
import com.xenomachina.argparser.runMain
import java.io.BufferedOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions

class GumballMachine(val classpath: List<Path>, val mainClassName: String) {
    private val mainClassNameLenLimit = 256
    val tmpdir = Files.createTempDirectory("gumball")

    init {
        require(mainClassName.length < mainClassNameLenLimit - 1) {
            "Main class name is too long, cannot be more than ${mainClassNameLenLimit - 1} characters"
        }
        Files.createDirectories(tmpdir)
        tmpdir.toFile().deleteOnExit()
    }

    fun make(): InputStream {
        // Create the unified JAR that contains the standard library and app classes together.
        // In future we should separate app and boot jar for better compatibility with existing code.
        val creator = BootJarCreator(classpath, tmpdir)
        Files.newOutputStream("/tmp/uberjar.jar.o".asPath).use { creator.bootjarAsELF.copyTo(it) }

        // TODO: ProGuard the boot jar.

        generateBootstrap()

        linkObjects()

        return Files.newInputStream(tmpdir / "app")
    }

    private fun generateBootstrap() {
        // Null terminate the name of the main class and edit into the bootstrap binary.
        val mainClassNameBytes = mainClassName.toByteArray().copyOf(mainClassName.length + 1)
        val editor = BinaryEditor('X'.toInt(), mainClassNameLenLimit, mainClassNameBytes)
        val bootstrapObjPath = tmpdir / "bootstrap.o"
        javaClass.getResourceAsStream("bootstrap-mac64.o").use { input ->
            BufferedOutputStream(Files.newOutputStream(bootstrapObjPath)).use { output ->
                editor.edit(input, output)
            }
        }
    }

    private fun linkObjects() {
        val archive = "libavian-mac64.a"
        javaClass.getResourceAsStream(archive).use { input ->
            input.copyTo(tmpdir / archive)
        }
        val cmd = "/usr/bin/g++ -rdynamic -Wl,-all_load $archive bootstrap.o uber.jar.o  -ldl -lpthread -lz -o app -framework CoreFoundation"
        run(tmpdir, cmd)
        run(tmpdir, "/usr/bin/strip -S -x app")
    }
}

class Args(parser: ArgParser) {
    val classpath by parser.storing(help = "Colon separated list of JARs to compile") { split(":").map { Paths.get(it) } }
    val mainClass by parser.positional("MAIN", help = "Name of the class containing the app's net.plan99.gumball.main method")
    val output by parser.positional("OUTPUT", help = "Path to the output file") { asPath }.addValidator {
        if (Files.isDirectory(value))
            throw SystemExitException("The output path must be a file name, not a directory", 1)
    }
}

fun main(args: Array<String>) {
    Args(ArgParser(args)).runMain("gumball") {
        GumballMachine(classpath, mainClass).make().copyTo(output)
        try {
            Files.setPosixFilePermissions(output, PosixFilePermissions.fromString("rwxr-x---"))
        } catch(e: UnsupportedOperationException) {
            // Not on UNIX
        }
    }
}