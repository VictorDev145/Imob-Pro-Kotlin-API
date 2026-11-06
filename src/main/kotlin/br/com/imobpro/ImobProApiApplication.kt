package br.com.imobpro

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class ImobProApiApplication

fun main(args: Array<String>) {
    runApplication<ImobProApiApplication>(*args)
}
