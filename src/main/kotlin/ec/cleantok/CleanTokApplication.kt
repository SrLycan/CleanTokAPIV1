package ec.cleantok

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class CleanTokApplication

fun main(args: Array<String>) {
    runApplication<CleanTokApplication>(*args)
}
