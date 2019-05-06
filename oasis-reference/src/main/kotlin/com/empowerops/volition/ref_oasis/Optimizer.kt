package com.empowerops.volition.ref_oasis

import com.empowerops.volition.dto.LoggingInterceptor
import com.google.common.eventbus.EventBus
import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.ServerInterceptors
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder
import javafx.application.Application
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.stage.Stage
import org.conscrypt.Conscrypt
import java.io.File
import java.security.KeyStore
import java.security.Security
import javax.net.ssl.KeyManagerFactory

fun main(args: Array<String>) {
    Application.launch(Optimizer::class.java)
}

class Optimizer : Application() {
    lateinit var server: Server
    lateinit var endpoint: OptimizerEndpoint
    lateinit var modelService: DataModelService
    val eventBus: EventBus = EventBus()

    override fun start(primaryStage: Stage) {
        val fxmlLoader = FXMLLoader()
        val resourceAsStream = this.javaClass.classLoader.getResourceAsStream("com.empowerops.volition.ref_oasis/OptimizerView.fxml")
        val root = fxmlLoader.load<Parent>(resourceAsStream)
        val controller = fxmlLoader.getController<OptimizerController>()
        primaryStage.title = "Volition Reference Optimizer"
        primaryStage.scene = Scene(root)
        primaryStage.show()

        setupService()
        val connectionView = ConnectionView(modelService, endpoint, eventBus)

        controller.setData(modelService, endpoint, eventBus, connectionView.root)
    }

    fun setupService() {
        modelService = DataModelService(eventBus)
        endpoint = OptimizerEndpoint(modelService, eventBus)
        Security.insertProviderAt(Conscrypt.newProvider(), 1)

//        val providers = Security.getProviders()
//        var x = KeyStore.getInstance("Conscrypt")
//
//        val x1 = KeyManagerFactory.getInstance("asdf")
//
//        val nettyChannel = NettyChannelBuilder
//                .forAddress("127.0.0.1", 5550)
//                .sslContext(GrpcSslContexts.configure(SslContextBuilder.forServer(x1)).build())
//                .build()
//
        //ok so my current plan:
        // use this https://www.sslsupportdesk.com/java-keytool-commands/
        // include keytool.exe in the distro
        // 1. call it to generate a cert
        // 2. point at that cert here.

        val caPathRoot = "C:\\Users\\Geoff\\Code\\volition\\sslcerts"

        server = ServerBuilder
                .forPort(5550)
                .useTransportSecurity(File("$caPathRoot/server.crt"), File("$caPathRoot/server.pem"))
                .addService(ServerInterceptors.intercept(endpoint, LoggingInterceptor(System.out)))
                .build()

//        var another = NettyChannelBuilder.forAddress("127.0.0.1", 5550).build()

        server.start()
    }
}
