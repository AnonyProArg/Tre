package com.example.localvpn

import android.content.Context
import android.os.ParcelFileDescriptor
import dalvik.system.DexFile
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

class LibboxRuntime(
    private val emitLog: (String) -> Unit
) {
    private var commandServer: Any? = null

    fun tryStart(context: Context, vpnInterface: ParcelFileDescriptor, configContent: String): Boolean {
        return try {
            val libboxClass = Class.forName("libbox.Libbox")
            emitLog("libbox detectado: ${libboxClass.name}")
            logDetectedLibboxClasses(context)

            val setupOptionsClass = Class.forName("libbox.SetupOptions")
            val setupOptions = setupOptionsClass.getDeclaredConstructor().newInstance()
            setMember(setupOptions, "BasePath", context.filesDir.absolutePath)
            setMember(setupOptions, "WorkingPath", File(context.filesDir, "libbox-work").absolutePath)
            setMember(setupOptions, "TempPath", context.cacheDir.absolutePath)
            setMember(setupOptions, "FixAndroidStack", true)
            setMember(setupOptions, "CommandServerListenPort", 0)
            setMember(setupOptions, "CommandServerSecret", "")
            setMember(setupOptions, "LogMaxLines", 400)
            setMember(setupOptions, "Debug", true)

            invokeStatic(libboxClass, listOf("Setup", "setup"), arrayOf(setupOptions))
            emitLog("libbox Setup() aplicado")

            val commandServer = createAndStartCommandServer(libboxClass, vpnInterface)
            val overrideOptions = createOverrideOptions()
            invokeAny(commandServer, listOf("StartOrReloadService", "startOrReloadService"), arrayOf(configContent, overrideOptions))

            this.commandServer = commandServer
            emitLog("libbox StartOrReloadService() completado")
            true
        } catch (t: Throwable) {
            emitLog("WARN libbox no pudo iniciar: ${t.message}")
            false
        }
    }

    fun stop() {
        val server = commandServer ?: return
        try {
            invokeAny(server, listOf("CloseService", "closeService"), emptyArray())
            invokeAny(server, listOf("Close", "close"), emptyArray())
            emitLog("libbox detenido")
        } catch (t: Throwable) {
            emitLog("WARN deteniendo libbox: ${t.message}")
        } finally {
            commandServer = null
        }
    }

    private fun createAndStartCommandServer(libboxClass: Class<*>, vpnInterface: ParcelFileDescriptor): Any {
        val handlerInterface = Class.forName("libbox.CommandServerHandler")
        val platformInterface = Class.forName("libbox.PlatformInterface")

        val handlerProxy = Proxy.newProxyInstance(
            handlerInterface.classLoader,
            arrayOf(handlerInterface),
            commandHandlerInvocationHandler()
        )

        val platformProxy = Proxy.newProxyInstance(
            platformInterface.classLoader,
            arrayOf(platformInterface),
            platformInvocationHandler(vpnInterface)
        )

        val server = invokeStatic(
            libboxClass,
            listOf("NewCommandServer", "newCommandServer"),
            arrayOf(handlerProxy, platformProxy)
        )

        invokeAny(server, listOf("Start", "start"), emptyArray())
        emitLog("libbox CommandServer iniciado")
        return server
    }

    private fun createOverrideOptions(): Any {
        val clazz = Class.forName("libbox.OverrideOptions")
        val instance = clazz.getDeclaredConstructor().newInstance()
        setMember(instance, "AutoRedirect", true)
        val emptyIterator = newEmptyStringIterator()
        setMember(instance, "IncludePackage", emptyIterator)
        setMember(instance, "ExcludePackage", emptyIterator)
        return instance
    }

    private fun commandHandlerInvocationHandler(): InvocationHandler {
        return InvocationHandler { _, method, _ ->
            when (method.name) {
                "ServiceStop", "serviceStop", "ServiceReload", "serviceReload",
                "SetSystemProxyEnabled", "setSystemProxyEnabled", "WriteDebugMessage", "writeDebugMessage" -> null
                "GetSystemProxyStatus", "getSystemProxyStatus" -> {
                    val statusClass = Class.forName("libbox.SystemProxyStatus")
                    val status = statusClass.getDeclaredConstructor().newInstance()
                    setMember(status, "Enabled", false)
                    setMember(status, "Available", false)
                    status
                }
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun platformInvocationHandler(vpnInterface: ParcelFileDescriptor): InvocationHandler {
        val emptyIterator by lazy { newEmptyStringIterator() }
        val emptyNetIfIterator by lazy { newEmptyNetworkInterfaceIterator() }

        return InvocationHandler { _, method, _ ->
            when (method.name) {
                "LocalDNSTransport", "localDNSTransport" -> null
                "UsePlatformAutoDetectInterfaceControl", "usePlatformAutoDetectInterfaceControl" -> false
                "AutoDetectInterfaceControl", "autoDetectInterfaceControl" -> null
                "OpenTun", "openTun" -> vpnInterface.fd
                "UseProcFS", "useProcFS" -> false
                "FindConnectionOwner", "findConnectionOwner" -> null
                "StartDefaultInterfaceMonitor", "startDefaultInterfaceMonitor" -> null
                "CloseDefaultInterfaceMonitor", "closeDefaultInterfaceMonitor" -> null
                "GetInterfaces", "getInterfaces" -> emptyNetIfIterator
                "UnderNetworkExtension", "underNetworkExtension" -> false
                "IncludeAllNetworks", "includeAllNetworks" -> false
                "ReadWIFIState", "readWIFIState" -> null
                "SystemCertificates", "systemCertificates" -> emptyIterator
                "ClearDNSCache", "clearDNSCache" -> null
                "SendNotification", "sendNotification" -> null
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun newEmptyStringIterator(): Any {
        val itf = Class.forName("libbox.StringIterator")
        return Proxy.newProxyInstance(itf.classLoader, arrayOf(itf)) { _, method, _ ->
            when (method.name) {
                "HasNext", "hasNext" -> false
                "Next", "next" -> ""
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun newEmptyNetworkInterfaceIterator(): Any {
        val itf = Class.forName("libbox.NetworkInterfaceIterator")
        return Proxy.newProxyInstance(itf.classLoader, arrayOf(itf)) { _, method, _ ->
            when (method.name) {
                "HasNext", "hasNext" -> false
                "Next", "next" -> null
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun defaultValue(type: Class<*>): Any? {
        if (!type.isPrimitive) return null
        return when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Character.TYPE -> '\u0000'
            else -> null
        }
    }

    private fun invokeStatic(target: Class<*>, names: List<String>, args: Array<Any?>): Any {
        val method = target.methods.firstOrNull { it.name in names && it.parameterCount == args.size }
            ?: error("Método estático no encontrado: ${names.joinToString()}")
        return method.invoke(null, *args) ?: Unit
    }

    private fun invokeAny(target: Any, names: List<String>, args: Array<Any?>): Any? {
        val method = target.javaClass.methods.firstOrNull { it.name in names && it.parameterCount == args.size }
            ?: error("Método no encontrado en ${target.javaClass.name}: ${names.joinToString()}")
        return method.invoke(target, *args)
    }

    private fun setMember(target: Any, name: String, value: Any?) {
        val javaClass = target.javaClass
        val field = javaClass.fields.firstOrNull { it.name == name }
        if (field != null) {
            field.set(target, value)
            return
        }

        val setterName = "set$name"
        val setter = javaClass.methods.firstOrNull { it.name == setterName && it.parameterCount == 1 }
        if (setter != null) {
            setter.invoke(target, value)
            return
        }

        emitLog("WARN libbox: miembro no encontrado para asignar $name en ${javaClass.name}")
    }

    private fun logDetectedLibboxClasses(context: Context) {
        try {
            val entries = DexFile(context.applicationInfo.sourceDir).entries().asSequence()
                .filter { it.startsWith("libbox.") }
                .take(20)
                .toList()
            emitLog("Clases libbox detectadas: ${entries.joinToString()}")
        } catch (t: Throwable) {
            emitLog("WARN listando clases libbox: ${t.message}")
        }
    }
}
