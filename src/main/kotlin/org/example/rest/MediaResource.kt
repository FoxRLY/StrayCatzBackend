package org.example.rest

import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.example.service.MediaService
import org.jboss.resteasy.reactive.RestForm
import org.jboss.resteasy.reactive.multipart.FileUpload
import java.net.URI
import java.util.UUID

/**
 * Картинки: загрузка (multipart, поле "file") и отдача по id.
 *
 * Отдача без авторизации: <img src> не умеет слать Authorization, а id —
 * случайный UUID, перебрать его нельзя.
 */
@Path("/api/media")
class MediaResource(
    private val currentUser: CurrentUser,
    private val media: MediaService,
) {
    /** multipart/form-data, поле file -> 201 {id, url, contentType, sizeBytes, createdAt} */
    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    fun upload(@HeaderParam("Authorization") auth: String?, @RestForm("file") file: FileUpload?): Response {
        val me = currentUser.require(auth)
        val f = file ?: throw ApiException.badRequest("no_file", "нужно поле file (multipart/form-data)")
        val out = media.upload(me.userId, f.uploadedFile(), f.size())
        return Response.status(201).entity(out).build()
    }

    /**
     * S3: 302 на presigned URL / CDN (браузер берёт файл напрямую, работает
     * перемотка видео). Локальный диск: отдаём поток сами.
     */
    @GET
    @Path("/{id}")
    fun get(@PathParam("id") id: UUID): Response {
        val m = media.find(id) ?: throw ApiException.notFound("файл не найден")
        media.directUrl(m)?.let { url ->
            return Response.temporaryRedirect(URI.create(url))
                // presigned-ссылка живёт час — кэшируем редирект заметно меньше
                .header("Cache-Control", "private, max-age=300")
                .build()
        }
        val stream = media.open(m) ?: throw ApiException.notFound("файл не найден")
        return Response.ok(stream, m.contentType)
            .header("Cache-Control", "public, max-age=31536000, immutable")
            .header("X-Content-Type-Options", "nosniff")
            .build()
    }
}
