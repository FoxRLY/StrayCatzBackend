package org.example.service

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.example.bus.EventBus
import org.example.proto.Envelope
import org.example.proto.FrameTypes
import org.example.rest.ApiException
import org.example.rest.ChatFolderIn
import org.example.rest.ChatFolderOut
import java.util.UUID

/**
 * Папки чатов — личные, у каждого свои (как в Telegram). Один чат может
 * лежать в нескольких папках. «Все чаты» — это просто GET /api/chats без
 * папки, отдельной записи не нужно. Чат, из которого человек вышел, в папке
 * остаётся, но в выдаче не показывается (GET /api/chats берёт только мои чаты).
 * Любая правка рассылает мне же кадр chat.folders — другие вкладки/устройства
 * перечитывают список.
 */
@ApplicationScoped
class ChatFolderService(
    private val em: EntityManager,
    private val mapper: ObjectMapper,
    private val bus: EventBus,
    private val chats: ChatService,
    private val chatList: ChatManagementService,
) {
    companion object {
        const val MAX_FOLDERS = 20
        const val MAX_CHATS = 500
        const val MAX_TITLE = 40
    }

    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun list(me: UUID): List<ChatFolderOut> {
        val folders = em.createNativeQuery(
            "select id, title, emoji, position from chat_folder where user_id = ?1 order by position, created_at",
        ).setParameter(1, me).resultList as List<Array<Any?>>
        if (folders.isEmpty()) return emptyList()
        // непрочитанное по папкам — из моего списка чатов (там уже посчитано)
        val all = chatList.listMine(me)
        return folders.map { f ->
            val id = f[0] as UUID
            val inFolder = all.filter { id in it.folderIds }
            ChatFolderOut(
                id = id,
                title = f[1] as String,
                emoji = f[2] as String?,
                position = (f[3] as Number).toInt(),
                chatIds = inFolder.map { it.chatId },
                unread = inFolder.sumOf { it.unread },
                unreadChats = inFolder.count { it.unread > 0 },
            )
        }
    }

    @Transactional
    fun create(me: UUID, req: ChatFolderIn): ChatFolderOut {
        val count = (em.createNativeQuery("select count(*) from chat_folder where user_id = ?1").setParameter(1, me).singleResult as Number).toInt()
        if (count >= MAX_FOLDERS) throw ApiException.badRequest("too_many_folders", "не больше $MAX_FOLDERS папок")
        val id = UUID.randomUUID()
        em.createNativeQuery("insert into chat_folder (id, user_id, title, emoji, position) values (?1, ?2, ?3, nullif(?4, ''), ?5)")
            .setParameter(1, id).setParameter(2, me).setParameter(3, title(req.title))
            .setParameter(4, emoji(req.emoji) ?: "").setParameter(5, count).executeUpdate()
        req.chatIds?.let { setChats(me, id, it) }
        changed(me)
        return one(me, id)
    }

    /** title/emoji: null — не менять, emoji "" — убрать. chatIds: null — не менять, иначе — заменить состав. */
    @Transactional
    fun update(me: UUID, id: UUID, req: ChatFolderIn): ChatFolderOut {
        own(me, id)
        req.title?.let {
            em.createNativeQuery("update chat_folder set title = ?2 where id = ?1").setParameter(1, id).setParameter(2, title(it)).executeUpdate()
        }
        req.emoji?.let {
            em.createNativeQuery("update chat_folder set emoji = nullif(?2, '') where id = ?1").setParameter(1, id).setParameter(2, emoji(it) ?: "").executeUpdate()
        }
        req.chatIds?.let { setChats(me, id, it) }
        changed(me)
        return one(me, id)
    }

    @Transactional
    fun delete(me: UUID, id: UUID) {
        own(me, id)
        em.createNativeQuery("delete from chat_folder_chat where folder_id = ?1").setParameter(1, id).executeUpdate()
        em.createNativeQuery("delete from chat_folder where id = ?1").setParameter(1, id).executeUpdate()
        changed(me)
    }

    /** Порядок папок: все мои id в нужном порядке. */
    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun reorder(me: UUID, ids: List<UUID>): List<ChatFolderOut> {
        val mine = em.createNativeQuery("select id from chat_folder where user_id = ?1", UUID::class.java)
            .setParameter(1, me).resultList as List<UUID>
        if (ids.toSet() != mine.toSet() || ids.size != mine.size) {
            throw ApiException.badRequest("invalid_order", "folderIds — все мои папки, каждая по разу")
        }
        ids.forEachIndexed { i, id ->
            em.createNativeQuery("update chat_folder set position = ?2 where id = ?1").setParameter(1, id).setParameter(2, i).executeUpdate()
        }
        changed(me)
        return list(me)
    }

    @Transactional
    fun addChat(me: UUID, id: UUID, chatId: UUID): ChatFolderOut {
        own(me, id)
        if (!chats.isMember(chatId, me)) throw ApiException.forbidden("ты не состоишь в этом чате")
        val n = (em.createNativeQuery("select count(*) from chat_folder_chat where folder_id = ?1").setParameter(1, id).singleResult as Number).toInt()
        if (n >= MAX_CHATS) throw ApiException.badRequest("folder_full", "в папке не больше $MAX_CHATS чатов")
        em.createNativeQuery("insert into chat_folder_chat (folder_id, chat_id, position) values (?1, ?2, ?3) on conflict do nothing")
            .setParameter(1, id).setParameter(2, chatId).setParameter(3, n).executeUpdate()
        changed(me)
        return one(me, id)
    }

    @Transactional
    fun removeChat(me: UUID, id: UUID, chatId: UUID): ChatFolderOut {
        own(me, id)
        em.createNativeQuery("delete from chat_folder_chat where folder_id = ?1 and chat_id = ?2")
            .setParameter(1, id).setParameter(2, chatId).executeUpdate()
        changed(me)
        return one(me, id)
    }

    // ------------------------------------------------------------------

    private fun setChats(me: UUID, id: UUID, raw: List<UUID>) {
        val ids = raw.distinct()
        if (ids.size > MAX_CHATS) throw ApiException.badRequest("folder_full", "в папке не больше $MAX_CHATS чатов")
        ids.forEach { if (!chats.isMember(it, me)) throw ApiException.forbidden("ты не состоишь в чате $it") }
        em.createNativeQuery("delete from chat_folder_chat where folder_id = ?1").setParameter(1, id).executeUpdate()
        ids.forEachIndexed { i, chatId ->
            em.createNativeQuery("insert into chat_folder_chat (folder_id, chat_id, position) values (?1, ?2, ?3)")
                .setParameter(1, id).setParameter(2, chatId).setParameter(3, i).executeUpdate()
        }
    }

    private fun one(me: UUID, id: UUID): ChatFolderOut =
        list(me).firstOrNull { it.id == id } ?: throw ApiException.notFound("папка не найдена")

    private fun own(me: UUID, id: UUID) {
        val ok = em.createNativeQuery("select 1 from chat_folder where id = ?1 and user_id = ?2")
            .setParameter(1, id).setParameter(2, me).resultList.isNotEmpty()
        if (!ok) throw ApiException.notFound("папка не найдена")
    }

    private fun title(v: String?): String {
        val t = v?.trim().orEmpty()
        if (t.isEmpty() || t.length > MAX_TITLE) throw ApiException.badRequest("invalid_title", "название папки: 1–$MAX_TITLE символов")
        return t
    }

    private fun emoji(v: String?): String? {
        if (v.isNullOrBlank()) return null
        return EmojiText.normalize(v) ?: throw ApiException.badRequest("invalid_emoji", "иконка папки — один эмодзи")
    }

    private fun changed(me: UUID) {
        bus.publishToUsers(listOf(me), Envelope(t = FrameTypes.CHAT_FOLDERS, d = mapper.valueToTree(mapOf("changed" to true))))
    }
}
