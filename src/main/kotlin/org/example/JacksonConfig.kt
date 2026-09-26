package org.example

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.quarkus.jackson.ObjectMapperCustomizer
import jakarta.inject.Singleton

@Singleton
class JacksonConfig : ObjectMapperCustomizer {
    override fun customize(mapper: ObjectMapper) {
        // Без Kotlin-модуля Jackson не умеет создавать data class'ы
        // (нет пустого конструктора, не видит значения по умолчанию) —
        // отсюда "не смогли распарсить кадр" на любом входящем кадре.
        // Нужна зависимость com.fasterxml.jackson.module:jackson-module-kotlin.
        mapper.registerKotlinModule()
        mapper.registerModule(JavaTimeModule())
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
}
