package net.perfectdreams.dreammobspawner

import com.github.salomonbrys.kotson.fromJson
import com.okkero.skedule.schedule
import net.perfectdreams.commands.annotation.Subcommand
import net.perfectdreams.commands.bukkit.SparklyCommand
import net.perfectdreams.dreamcore.utils.*
import net.perfectdreams.dreamcore.utils.commands.AbstractCommand
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntitySpawnEvent
import java.io.File

class DreamMobSpawner : KotlinPlugin(), Listener {
	var spawners = mutableListOf<SpawnerRegion>()
	var override = false

	val saveFile by lazy { File(dataFolder, "spawners.json") }

	override fun softEnable() {
		super.softEnable()

		registerEvents(this)
		dataFolder.mkdirs()

		if (saveFile.exists())
			spawners = DreamUtils.gson.fromJson(saveFile.readText())

		registerCommand(DreamMobSpawnerCommand(this))
		scheduler().schedule(this) {
			while (true) {
				waitFor(100)

				for (spawner in spawners) {
					// To avoid spawning mobs, we are only going to spawn if there is players nearby
					val shouldSpawn = Bukkit.getOnlinePlayers()
						.asSequence()
						.filter { it.world == spawner.spawn.world }
						.filter { 256 >= it.location.distanceSquared(spawner.spawn) }
						.any()

					spawner.spawnedMobs.filter { !WorldGuardUtils.isWithinRegion(it.location, spawner.region) }
						.forEach { it.remove() }
					val valid = spawner.spawnedMobs.filter { !it.isDead }.toMutableList()

					spawner.spawnedMobs = valid

					if (shouldSpawn) {
						while (3 > spawner.spawnedMobs.size) {
							override = true
							val spawned = spawner.spawn.world.spawnEntity(spawner.spawn.clone().add(0.0, 1.0, 0.0), spawner.type)
							override = false
							spawner.spawnedMobs.add(spawned)
						}
					}
				}
			}
		}
	}

	@EventHandler(ignoreCancelled = false, priority = EventPriority.MONITOR)
	fun onSpawn(event: EntitySpawnEvent) {
		if (override)
			event.isCancelled = false
	}

	@EventHandler(ignoreCancelled = true)
	fun onDamage(event: EntityDamageByEntityEvent) {
		val entity = event.entity
		val damager = event.damager

		if (damager is Player && entity is LivingEntity) {
			val isDead = 0 >= entity.health - event.finalDamage
			if (isDead) {
				val spawner = spawners.firstOrNull { WorldGuardUtils.isWithinRegion(entity.location, it.region) } ?: return

				if (spawner.price < 1)
					return

				// Player não possui dinheiro suficiente para pagar?
				if (spawner.price > damager.balance) {
					event.isCancelled = true
					damager.sendMessage("§cVocê precisa ter §2+${spawner.price - damager.balance} Sonhos§c para poder matar este pobre animal!")
					return
				} else {
					damager.balance -= spawner.price
					damager.sendMessage("§7Você pagou §2${spawner.price} Sonhos§7 para matar este pobre animal!")
				}
			}
		}
	}

	override fun softDisable() {
		super.softDisable()
	}

	class SpawnerRegion(
		val spawn: Location,
		val type: EntityType,
		val region: String,
		val price: Double) {
		@Transient
		var spawnedMobs = mutableListOf<Entity>()

		constructor() : this(Location(null, 0.0, 0.0, 0.0), EntityType.UNKNOWN, "???", 0.0) {
			spawnedMobs = mutableListOf<Entity>()
		}
	}

	class DreamMobSpawnerCommand(val m: DreamMobSpawner) : SparklyCommand(arrayOf("dreammobspawner"), permission = "dreammobspawner.admin") {

		@Subcommand
		fun root(sender: Player) {
			sender.sendMessage("§e/dreammobspawner add type region price")
			sender.sendMessage("§e/dreammobspawner remove region")
		}

		@Subcommand(["add"])
		fun add(sender: Player, type: String, region: String, price: String) {
			val price = price.toDouble()
			val type = EntityType.valueOf(type)

			val spawner = SpawnerRegion(
				sender.location,
				type,
				region,
				price
			)

			m.spawners.add(spawner)
			sender.sendMessage("§aSpawner de ${type.name} criado com sucesso!")

			m.saveFile.writeText(DreamUtils.gson.toJson(m.spawners))
		}

		@Subcommand(["remove"])
		fun remove(sender: Player, region: String) {
			val spawner = m.spawners.firstOrNull { it.region == region }

			if (spawner == null) {
				sender.sendMessage("§cNão existe nenhuma região com este nome!")
				return
			}

			m.spawners.remove(spawner)
			sender.sendMessage("§aSpawner removido com sucesso!")

			m.saveFile.writeText(DreamUtils.gson.toJson(m.spawners))
		}
	}
}