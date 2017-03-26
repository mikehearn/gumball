package net.plan99.gumball

import java.io.InputStream
import java.io.OutputStream

class BinaryEditor(private val needleByte: Int, private val repeatCount: Int, private val substitution: ByteArray) {
    /**
     * Copies the input into the output, but when a sequence of the given [needleByte] repeated [repeatCount]
     * times is found, the given [substitution] is written in its place with enough [needleByte]s written
     * to ensure the file doesn't change size (no internal file offsets are altered).
     */
    fun edit(inputStream: InputStream, outputStream: OutputStream) {
        require(inputStream.markSupported()) { "InputStream must support marking" }

        while (true) {
            val b = inputStream.read()
            if (b == -1) {
                return
            } else if (b != needleByte) {
                outputStream.write(b)
            } else {
                var quantity = 1
                while (true) {
                    val ib = inputStream.read()
                    if (ib == -1) {
                        return
                    } else if (ib == needleByte) {
                        quantity++
                        if (quantity == repeatCount) {
                            outputStream.write(substitution)
                            repeat(quantity - substitution.size) { outputStream.write(needleByte) }
                            break
                        }
                    } else {
                        repeat(quantity) { outputStream.write(b) }
                        outputStream.write(ib)
                        break
                    }
                }
            }
        }
    }
}