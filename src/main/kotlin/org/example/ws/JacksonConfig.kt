package org.example.ws

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.quarkus.jackson.ObjectMapperCustomizer
import jakarta.inject.Singleton

@Singleton
class JacksonConfig : ObjectMapperCustomizer {
    override fun customize(mapper: ObjectMapper) {
        mapper.registerModule(JavaTimeModule())
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
}
