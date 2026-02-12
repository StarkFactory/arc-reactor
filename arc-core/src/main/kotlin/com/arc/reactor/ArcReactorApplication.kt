package com.arc.reactor

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class ArcReactorApplication

fun main(args: Array<String>) {
    runApplication<ArcReactorApplication>(*args)
}
