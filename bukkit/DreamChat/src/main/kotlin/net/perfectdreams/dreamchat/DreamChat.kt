package net.perfectdreams.dreamchat

import club.minnced.discord.webhook.WebhookClient
import club.minnced.discord.webhook.send.AllowedMentions
import club.minnced.discord.webhook.send.WebhookMessageBuilder
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.kevinsawicki.http.HttpRequest
import com.github.salomonbrys.kotson.array
import com.github.salomonbrys.kotson.fromJson
import com.github.salomonbrys.kotson.string
import com.gmail.nossr50.datatypes.skills.PrimarySkillType
import com.gmail.nossr50.mcMMO
import com.google.common.collect.Sets
import com.greatmancode.craftconomy3.Common
import com.greatmancode.craftconomy3.groups.WorldGroupsManager
import com.okkero.skedule.SynchronizationContext
import com.okkero.skedule.schedule
import kotlinx.coroutines.delay
import net.perfectdreams.dreamchat.commands.*
import net.perfectdreams.dreamchat.commands.declarations.TellCommand
import net.perfectdreams.dreamchat.dao.ChatUser
import net.perfectdreams.dreamchat.dao.DiscordAccount
import net.perfectdreams.dreamchat.dao.EventMessage
import net.perfectdreams.dreamchat.listeners.ChatListener
import net.perfectdreams.dreamchat.listeners.SignListener
import net.perfectdreams.dreamchat.tables.ChatUsers
import net.perfectdreams.dreamchat.tables.PremiumUsers
import net.perfectdreams.dreamchat.tables.DiscordAccounts
import net.perfectdreams.dreamchat.tables.EventMessages
import net.perfectdreams.dreamchat.utils.DiscordAccountInfo
import net.perfectdreams.dreamchat.utils.bot.PantufaResponse
import net.perfectdreams.dreamchat.utils.bot.responses.*
import net.perfectdreams.dreamchat.utils.chatevent.EventoChatHandler
import net.perfectdreams.dreamcore.DreamCore
import net.perfectdreams.dreamcore.utils.*
import net.perfectdreams.dreamcore.utils.discord.DiscordWebhook
import org.bukkit.Bukkit
import org.bukkit.Statistic
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class DreamChat : KotlinPlugin() {
	companion object {
		val CHAT_REGEX = Regex("§[a-f0-9]")
		val FORMATTING_REGEX = Regex("§[k-or]")
		var mutedUsers = mutableSetOf<String>()
		var botResponses = mutableListOf<PantufaResponse>()
		var BOT_PREFIX = "§8[§6§lSuporte§8] "
		var LORITTA_PREFIX = "§8[§d§lDeusa Suprema§8] "
		var BOT_NAME = "§ePantufa"
		var LORITTA_NAME = "§b§lLoritta §3§lMorenitta"
		val chatWebhooks = mutableListOf<WebhookClient>()
		var currentWebhookIdx = 0
		lateinit var INSTANCE: DreamChat
		const val LAST_CHAT_WINNER_PATH = "last-chat-winner"
	}

	var pmLog = File(dataFolder, "privado.log")
	var chatLog = File(dataFolder, "chat.log")
	var topEntries = arrayOf("???", "???", "???")
	var topMcMMOPlayer: String? = null
	var topPlayerSkills = mutableMapOf<PrimarySkillType, String?>()
	var lockedTells = WeakHashMap<Player, String>()
	var quickReply = WeakHashMap<Player, Player>()
	var oldestPlayers = listOf<Pair<UUID, Int>>()
	val hideTells = Collections.newSetFromMap(WeakHashMap<Player, Boolean>())
	val eventoChat = EventoChatHandler()

	val replacers = mutableMapOf<Regex, String>()

	val cachedDiscordAccounts = Caffeine.newBuilder().maximumSize(1_000)
		.expireAfterWrite(3, TimeUnit.DAYS)
		.build<Long, Optional<DiscordAccountInfo>>()
		.asMap()
	var tellMessagesWebhook: WebhookClient? = null
	val tellMessagesQueue = ConcurrentLinkedQueue<String>()

	val dataYaml by lazy {
		File(dataFolder, "data.yml")
	}

	val userData by lazy {
		if (!dataYaml.exists())
			dataYaml.writeText("")

		YamlConfiguration.loadConfiguration(dataYaml)
	}

	override fun softEnable() {
		super.softEnable()
		INSTANCE = this
		dataFolder.mkdirs()

		// Fuck this
		val knownCommands = Bukkit.getCommandMap().knownCommands
		knownCommands.filter { it.value.label == "dreamchat" || it.value.label == "ignore" || it.value.label == "mute" || it.value.label == "nick" || it.value.label == "querotag" || it.value.label == "quicreply" || it.value.label == "tell" }.forEach {
			knownCommands.remove(it.key)
		}

		transaction(Databases.databaseNetwork) {
			SchemaUtils.createMissingTablesAndColumns(
				ChatUsers,
				EventMessages,
				PremiumUsers,
				DiscordAccounts
			)
		}

		DreamCore.INSTANCE.dreamEventManager.events.add(eventoChat)
		if (userData.contains(LAST_CHAT_WINNER_PATH)) {
			eventoChat.lastWinner = UUID.fromString(userData.getString(LAST_CHAT_WINNER_PATH))
		}

		registerEvents(ChatListener(this))
		registerEvents(SignListener(this))
		registerCommand(MuteCommand())
		registerCommand(
			TellCommand,
			TellExecutor(this),
			TellUnlockExecutor(this),
			TellLockExecutor(this)
		)
		registerCommand(QuickReplyCommand(this))
		registerCommand(NickCommand(this))
		registerCommand(QueroTagCommand(this))
		registerCommand(IgnoreCommand(this))
		registerCommand(DreamChatCommand)
		registerCommand(DreamChatReloadCommand)
		registerCommand(DreamChatSeeTellCommand)
		registerCommand(DreamChatStartCommand)
		registerCommand(OnlineCommand)
		reload()
		loadResponses()

		eventoChat.randomMessagesEvent.loadDatabaseMessages()

		scheduler().schedule(this, SynchronizationContext.ASYNC) {
			while (true) {
				// UPDATE ONLINE PLAYER TIME
				switchContext(SynchronizationContext.SYNC)
				// Running in a async thread may cause issues
				val onlinePlayers = Bukkit.getOnlinePlayers().toList()
				switchContext(SynchronizationContext.ASYNC)

				onlinePlayers.forEach { player ->
					transaction(Databases.databaseNetwork) {
						val chatUser = ChatUser.findById(player.uniqueId) ?: ChatUser.new(player.uniqueId) {}

						chatUser.playOneMinute = player.getStatistic(Statistic.PLAY_ONE_MINUTE)
					}
				}

				// GET TOP PLAYERS
				oldestPlayers = transaction(Databases.databaseNetwork) {
					ChatUsers.select { ChatUsers.playOneMinute.isNotNull() }
						.orderBy(ChatUsers.playOneMinute, SortOrder.DESC)
						.limit(10)
						.map { it[ChatUsers.id].value to (it[ChatUsers.playOneMinute] ?: 0) }
				}
				waitFor(20 * 15)
			}
		}

		scheduler().schedule(this, SynchronizationContext.ASYNC) {
			while (true) {
				// CRAFTCONOMY - TOP
				val currency = Common.getInstance().currencyManager.defaultCurrency
				val entries = Common.getInstance().storageHandler.storageEngine.getTopEntry(1, currency, WorldGroupsManager.DEFAULT_GROUP_NAME)

				for (idx in 0 until Math.min(3, entries.size)) {
					topEntries[idx] = entries[idx].username
				}

				// MCMMO - TOP
				topMcMMOPlayer = getTopPlayerInMcMMOSkill(null)
				for (skill in PrimarySkillType.values().filter { it != PrimarySkillType.SALVAGE && it != PrimarySkillType.SMELTING }) {
					topPlayerSkills[skill] = getTopPlayerInMcMMOSkill(skill)
				}

				waitFor(20 * 15)
			}
		}

		launchAsyncThread {
			while (true) {
				val builder = StringBuilder()

				while (tellMessagesQueue.isNotEmpty()) {
					val firstElement = tellMessagesQueue.peek()

					// Current length + First Element + "\n"
					// If it will overflow, we break the loop and send the message as is
					if (builder.length + firstElement.length + 1 > 2000)
						break

					// Append the message content
					builder.append(firstElement)
					builder.append("\n")

					// And remove the current message!
					tellMessagesQueue.remove()
				}

				if (builder.isNotEmpty()) {
					try {
						// Send the message if the content is not empty
						tellMessagesWebhook?.send(
							WebhookMessageBuilder()
								.setUsername("Mensagens Privadas \uD83D\uDC40")
								.setAvatarUrl("https://cdn.discordapp.com/emojis/726559073545486476.png?v=1")
								.setContent(builder.toString())
								.setAllowedMentions(AllowedMentions.none())
								.build()
						)
					} catch (e: Exception) {
						e.printStackTrace()
					}
				}

				delay(1_000)
			}
		}
	}

	override fun softDisable() {
		DreamChat.INSTANCE.saveConfig()
		DreamCore.INSTANCE.dreamEventManager.events.remove(eventoChat)
	}

	fun reload() {
		reloadConfig()
		replacers.clear()

		val yamlReplacers = config.getConfigurationSection("replacers")!!.getValues(false)

		yamlReplacers.forEach {
			replacers[it.key.toRegex(RegexOption.IGNORE_CASE)] = it.value as String
		}

		// Shutdown all current active webhooks
		chatWebhooks.forEach { it.close() }
		chatWebhooks.clear()
		config.getStringList("chat-webhooks").forEach {
			chatWebhooks += WebhookClient.withUrl(it)
		}

		tellMessagesWebhook?.close()
		tellMessagesWebhook = WebhookClient.withUrl(config.getString("tell-webhook")!!)

		// Evento Chat
		val eventSource = File(dataFolder, "evento-chat-messages.txt")

		if (eventSource.exists()) {
			// Ao ler o texto, vamos recolocar tudo que a gente precisa (mensagens, etc)
			val messages = eventSource.readLines()

			this@DreamChat.logger.info("Processando ${messages.size} mensagens para o evento chat...")

			transaction(Databases.databaseNetwork) { // Isto irá deletar TODAS as mensagens que não estão mais no "evento-chat-messages.txt"
				val storedMessages = EventMessage.all().toMutableList()

				val invalidMessages = storedMessages.filter { !messages.contains(it.message) }

				invalidMessages.forEach {
					this@DreamChat.logger.info("Deletando mensagem ${it.message} da database...")
					it.delete()
				}
			}

			transaction(Databases.databaseNetwork) { // E agora vamos inserir TODAS que estão no arquivo, mas não estão na db
				val storedMessages = EventMessage.all().toMutableList()

				val needsToBeInsertedMessages = messages.filter { !storedMessages.map { it.message }.contains(it) }

				for (message in needsToBeInsertedMessages) {
					this@DreamChat.logger.info("Inserindo nova mensagem ${message} na database!")
					EventMessage.new {
						this.message = message
						this.lastWinner = null
						this.timeElapsed = null
					}
				}
			}

			this@DreamChat.logger.info("Concluido! Todas as mensagens no arquivo foram processadas!")
		}
	}

	fun loadResponses() {
		botResponses.clear()

		val regexResponses = DreamUtils.gson.fromJson<List<RegExResponse>>(File(dataFolder, "responses.json").readText())
		regexResponses.forEach {
			botResponses.add(it)
			it.regex.mapTo(it.patterns) { it.toPattern(Pattern.CASE_INSENSITIVE) }
		}

		// botResponses.add(VoteCountResponse())
		botResponses.add(MarriedResponse())
		botResponses.add(LorittaAssinaResponse())
		botResponses.add(AssinaResponse())
		botResponses.add(PingResponse())
		botResponses.add(LagResponse())
	}

	private fun getTopPlayerInMcMMOSkill(skill: PrimarySkillType?) = mcMMO.getDatabaseManager().readLeaderboard(skill, 1, 1).firstOrNull()?.name
}
