package net.plan99.gumball

import java.io.InputStream
import java.nio.file.Files.*
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission.*

val String.asPath: Path get() = Paths.get(this)
operator fun Path.div(other: Path): Path = resolve(other)
operator fun Path.div(other: String): Path = resolve(other)
fun InputStream.copyTo(path: Path) {
    use { newOutputStream(path).use { out -> it.copyTo(out) } }
}

class SubprocessFailed(name: String, retcode: Int) : Exception("Subprocess ${name} failed with error code $retcode")

fun run(workingDir: Path, cmd: String) {
    run(workingDir, *cmd.split(' ').toTypedArray())
}

fun run(workingDir: Path, vararg args: Any) {
    if (isNotWindows) {
        val prog = args[0].toString().asPath
        if (OWNER_EXECUTE !in getPosixFilePermissions(workingDir / prog))
            setPosixFilePermissions(workingDir / prog, setOf(OWNER_EXECUTE, OWNER_WRITE, OWNER_READ))
    }
    val retcode = ProcessBuilder(*args.map(Any::toString).toTypedArray())
            .directory(workingDir.toFile())
            .inheritIO()
            .start()
            .waitFor()
    if (retcode != 0) {
        throw SubprocessFailed(args[0].toString(), retcode)
    }
}

private val isNotWindows: Boolean get() = "windows" !in System.getProperty("os.name").toLowerCase()