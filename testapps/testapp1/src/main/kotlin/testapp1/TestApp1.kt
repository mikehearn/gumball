package testapp1

import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.runMain

class Args(parser: ArgParser) {
    val foo by parser.storing(help = "Some foo")
    val bar by parser.storing(help = "Some bar")
}

fun main(args: Array<String>) = Args(ArgParser(args)).runMain("TestApp1") {
    println("Your message is: $foo $bar")
}
