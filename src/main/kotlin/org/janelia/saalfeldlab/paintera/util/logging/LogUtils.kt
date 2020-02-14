package org.janelia.saalfeldlab.paintera.util.logging

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.util.ContextInitializer
import picocli.CommandLine
import java.io.File
import java.lang.invoke.MethodHandles
import ch.qos.logback.classic.Logger as LogbackLogger

class LogUtils {

    companion object {

        @JvmStatic
        val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)

        private val rootLoggerLogback = rootLogger as? LogbackLogger

        private const val rootLoggerLevelProperty = "paintera.root.logger.level"

        @JvmStatic
        var rootLoggerLevel: Level?
            @Synchronized set(level) {
                if (level === null)
                    System.clearProperty(rootLoggerLevelProperty)
                else {
                    setLogLevelFor(rootLogger, level)
                    System.setProperty(rootLoggerLevelProperty, level.levelStr)
                }
            }
            @Synchronized get() = rootLoggerLogback?.level

        @JvmStatic
        fun setPainteraLogDir(file: File) = setPainteraLogDir(file.path)

        @JvmStatic
        fun setPainteraLogDir(path: String) {
            System.setProperty("paintera.log.dir", path)
            resetLoggingConfig()
        }

        @JvmStatic
        fun resetLoggingConfig() {
            val lc = LoggerFactory.getILoggerFactory() as LoggerContext
            val ci = ContextInitializer(lc)
            lc.reset()
            ci.autoConfig()
        }

        @JvmStatic
        fun setLogLevelFor(name: String, level: Level) = setLogLevelFor(LoggerFactory.getLogger(name), level)

        @JvmStatic
        fun setLogLevelFor(logger: Logger, level: Level) = if (logger is LogbackLogger) logger.level = level else Unit

        fun String.isRootLoggerName() = ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME == this
    }

    class Logback {
        class Loggers {
            companion object {
                operator fun get(name: String) = LoggerFactory.getLogger(name) as? ch.qos.logback.classic.Logger
            }
        }

        class Levels {
            companion object {

                @JvmStatic
                val levels = arrayOf(
                    Level.ALL,
                    Level.TRACE,
                    Level.DEBUG,
                    Level.INFO,
                    Level.WARN,
                    Level.ERROR,
                    Level.OFF).sortedBy { it.levelInt }.asReversed()

                operator fun get(level: String): Level? = levels.firstOrNull { level.equals(it.levelStr, ignoreCase = true) }

                operator fun contains(level: String): Boolean = get(level) != null

            }

            class CmdLineConverter : CommandLine.ITypeConverter<Level?> {
                override fun convert(value: String): Level? {
                    val level = Levels[value]
                    if (level === null)
                        LOG.warn("Ignoring invalid log level `{}'. Valid levels are (case-insensitive): {}", value, levels)
                    return level
                }

                companion object {
                    val LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass())
                }

            }
        }

    }

}
