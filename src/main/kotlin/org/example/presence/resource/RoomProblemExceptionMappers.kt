package org.example.resource

import jakarta.validation.ConstraintViolationException
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import org.example.presence.dto.Problem

private const val PROBLEM_JSON = "application/problem+json"

// ВНИМАНИЕ: если из части про людей/присутствие уже есть свой
// ExceptionMapper<NotFoundException> — удалите RoomNotFoundExceptionMapper ниже,
// два маппера на один и тот же тип исключения одновременно не зарегистрируются.

@Provider
class ForbiddenExceptionMapper : ExceptionMapper<ForbiddenException> {
    override fun toResponse(exception: ForbiddenException): Response =
        Response.status(Response.Status.FORBIDDEN)
            .type(PROBLEM_JSON)
            .entity(Problem(title = "Forbidden", status = 403, detail = exception.message))
            .build()
}

@Provider
class RoomNotFoundExceptionMapper : ExceptionMapper<NotFoundException> {
    override fun toResponse(exception: NotFoundException): Response =
        Response.status(Response.Status.NOT_FOUND)
            .type(MediaType.APPLICATION_JSON)
            .entity(Problem(title = "Not Found", status = 404, detail = exception.message))
            .build()
}

/** Невалидное тело запроса (например, RoomConfig) -> 422, как того требует спека. */
@Provider
class ConstraintViolationExceptionMapper : ExceptionMapper<ConstraintViolationException> {
    override fun toResponse(exception: ConstraintViolationException): Response {
        val detail = exception.constraintViolations.joinToString("; ") { "${it.propertyPath}: ${it.message}" }
        return Response.status(422)
            .type(MediaType.APPLICATION_JSON)
            .entity(Problem(title = "Unprocessable Entity", status = 422, detail = detail))
            .build()
    }
}
