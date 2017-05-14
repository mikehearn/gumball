TODO
====

* Gradle plugin
* AOT compilation
* Windows
* Linux
* Test with more apps
* SWT
* JNA?
* Investigate why LZMA is not a bigger win
* Documentation, website, announcement

Making the included binaries
============================

In src/main/resources there are the following files:

* avian-stdlib.jar
* binaryToObject-mac64
* bootstrap-mac64.o
* libavian-mac64.a

and a couple of text files.

To generate these files, compile Avian like so:

    make openjdk=/Library/Java/JavaVirtualMachines/jdk1.8.0_102.jdk/Contents/Home openjdk-src=../jdk8u/jdk/src/ lzma=../lzma-sdk/

where the LZMA SDK must be 9.20 and no higher version. Then run:

    cp avian/build/macosx-x86_64-lzma-openjdk-src/classpath.jar src/main/resources/net/plan99/gumball/avian-stdlib.jar
    cp avian/build/macosx-x86_64-lzma-openjdk-src/libavian.a src/main/resources/net/plan99/gumball/mac64/libavian.a
    cp avian/build/macosx-x86_64-lzma-openjdk-src/binaryToObject/binaryToObject src/main/resources/net/plan99/gumball/mac64/binaryToObject

to get the two Avian/OpenJDK files. To create the bootstrap file,

    cd src/main/native
    g++ -I$JAVA_HOME/include -I$JAVA_HOME/include/linux -I$JAVA_HOME/include/darwin -c bootstrap.cpp -o ../resources/net/plan99/gumball/mac64/bootstrap.o
    
License
=======

Apache 2