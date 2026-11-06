package br.com.imobpro

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class ImobProApiApplication

fun main(args: Array<String>) {
    runApplication<ImobProApiApplication>(*args)
}
