package org.example.service

import io.quarkus.logging.Log
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import org.eclipse.microprofile.config.inject.ConfigProperty
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.NoSuchBucketException
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.Optional

/**
 * Где физически лежат файлы. storage_key в таблице media — путь на диске
 * или ключ объекта в бакете (формат одинаковый: 2026/09/<uuid>.png), так что
 * переезд между хранилищами — это просто копирование файлов.
 */
interface MediaStorage {
    fun put(key: String, source: Path, contentType: String)
    fun open(key: String): InputStream?
    fun delete(key: String)

    /** Прямая ссылка для браузера (S3), null — отдавать через GET /api/media/{id}. */
    fun directUrl(key: String): String? = null
}

/** Локальный диск — для разработки без MinIO (straycatz.media.storage=local). */
class LocalDiskMediaStorage(dir: String) : MediaStorage {
    private val root: Path = Paths.get(dir).toAbsolutePath().normalize()

    init {
        Files.createDirectories(root)
        Log.infof("медиа: локальный диск %s", root)
    }

    override fun put(key: String, source: Path, contentType: String) {
        val target = resolve(key)
        Files.createDirectories(target.parent)
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun open(key: String): InputStream? {
        val p = resolve(key)
        return if (Files.isRegularFile(p)) Files.newInputStream(p) else null
    }

    override fun delete(key: String) {
        Files.deleteIfExists(resolve(key))
    }

    private fun resolve(key: String): Path {
        val p = root.resolve(key).normalize()
        require(p.startsWith(root)) { "ключ выходит за пределы каталога медиа" }
        return p
    }
}

/**
 * S3-совместимое хранилище: MinIO локально, в проде — AWS S3, Yandex Object
 * Storage, Selectel и т.п. (меняются только endpoint/ключи в конфиге).
 *
 * Браузер получает файл НЕ через бэкенд: GET /api/media/{id} отвечает 302 на
 * presigned URL (или на публичный адрес/CDN, если задан public-url). Так
 * работают Range-запросы и перемотка видео, а бэкенд не гоняет байты.
 */
class S3MediaStorage(
    private val s3: S3Client,
    private val presigner: S3Presigner,
    private val bucket: String,
    private val presignTtl: Duration,
    private val publicUrl: String?,
) : MediaStorage {

    override fun put(key: String, source: Path, contentType: String) {
        s3.putObject(
            PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .contentLength(Files.size(source))
                // ключ содержит uuid и никогда не переписывается — кэшируем навсегда
                .cacheControl("public, max-age=31536000, immutable")
                .build(),
            RequestBody.fromFile(source),
        )
    }

    override fun open(key: String): InputStream? = try {
        s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())
    } catch (e: NoSuchKeyException) {
        null
    }

    override fun delete(key: String) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build())
    }

    override fun directUrl(key: String): String {
        publicUrl?.let { return "${it.trimEnd('/')}/$key" }
        return presigner.presignGetObject(
            GetObjectPresignRequest.builder()
                .signatureDuration(presignTtl)
                .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
                .build(),
        ).url().toString()
    }

    /** В dev создаём бакет сами, чтобы не ходить в консоль MinIO. */
    fun ensureBucket() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build())
        } catch (e: NoSuchBucketException) {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build())
            Log.infof("медиа: создали бакет %s", bucket)
        } catch (e: S3Exception) {
            if (e.statusCode() == 404) {
                s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build())
                Log.infof("медиа: создали бакет %s", bucket)
            } else {
                throw e
            }
        }
    }
}

/**
 * Выбор хранилища по straycatz.media.storage (s3 | local). Продюсер, а не
 * два @ApplicationScoped-бина: иначе CDI не поймёт, какой MediaStorage внедрять.
 */
@ApplicationScoped
class MediaStorageProducer(
    @ConfigProperty(name = "straycatz.media.storage", defaultValue = "s3") private val kind: String,
    @ConfigProperty(name = "straycatz.media.dir", defaultValue = "data/media") private val dir: String,
    @ConfigProperty(name = "straycatz.s3.endpoint") private val endpoint: Optional<String>,
    @ConfigProperty(name = "straycatz.s3.region", defaultValue = "us-east-1") private val region: String,
    @ConfigProperty(name = "straycatz.s3.bucket", defaultValue = "straycatz-media") private val bucket: String,
    @ConfigProperty(name = "straycatz.s3.access-key") private val accessKey: Optional<String>,
    @ConfigProperty(name = "straycatz.s3.secret-key") private val secretKey: Optional<String>,
    @ConfigProperty(name = "straycatz.s3.path-style", defaultValue = "true") private val pathStyle: Boolean,
    @ConfigProperty(name = "straycatz.s3.presign-ttl", defaultValue = "PT1H") private val presignTtl: Duration,
    @ConfigProperty(name = "straycatz.s3.public-url") private val publicUrl: Optional<String>,
    @ConfigProperty(name = "straycatz.s3.create-bucket", defaultValue = "false") private val createBucket: Boolean,
) {
    @Produces
    @ApplicationScoped
    fun mediaStorage(): MediaStorage {
        if (kind.equals("local", ignoreCase = true)) return LocalDiskMediaStorage(dir)
        require(kind.equals("s3", ignoreCase = true)) { "straycatz.media.storage: s3 или local, а не '$kind'" }

        val credentials = StaticCredentialsProvider.create(
            AwsBasicCredentials.create(
                accessKey.orElseThrow { IllegalStateException("нужен straycatz.s3.access-key") },
                secretKey.orElseThrow { IllegalStateException("нужен straycatz.s3.secret-key") },
            ),
        )
        val s3Config = S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build()

        val clientBuilder = S3Client.builder()
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .region(Region.of(region))
            .credentialsProvider(credentials)
            .serviceConfiguration(s3Config)
            // S3-совместимые хранилища (MinIO, Yandex) не всегда понимают новые
            // контрольные суммы AWS SDK — считаем их только когда обязательно
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
        val presignerBuilder = S3Presigner.builder()
            .region(Region.of(region))
            .credentialsProvider(credentials)
            .serviceConfiguration(s3Config)
        endpoint.ifPresent {
            clientBuilder.endpointOverride(URI.create(it))
            presignerBuilder.endpointOverride(URI.create(it))
        }

        val storage = S3MediaStorage(
            clientBuilder.build(),
            presignerBuilder.build(),
            bucket,
            presignTtl,
            publicUrl.orElse(null),
        )
        if (createBucket) storage.ensureBucket()
        Log.infof("медиа: S3 %s, бакет %s", endpoint.orElse("aws"), bucket)
        return storage
    }
}
