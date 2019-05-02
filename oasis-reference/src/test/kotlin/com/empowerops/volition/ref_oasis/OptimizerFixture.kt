package com.empowerops.volition.ref_oasis

//import com.empowerops.volition.dto.OASISQueryDTO
//import com.empowerops.volition.dto.RegistrationCommandDTO
import com.google.common.eventbus.EventBus
import com.nhaarman.mockitokotlin2.*
import io.grpc.stub.StreamObserver
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

internal class OptimizerFixture {
    @Test
    fun `when optimizer ask to register a node register`() {
//        //setup
//        val eventBus = mock<EventBus>()
//        val modelService = mock<DataModelService>()
//        val fakeStreamObserver = mock<StreamObserver<OASISQueryDTO>>()
//
//        val registrationDTO = RegistrationCommandDTO.newBuilder().setName("TestNode1").build()
//        val optimizerEndpoint = OptimizerEndpoint(modelService, eventBus)
//
//        //act
//        optimizerEndpoint.register(registrationDTO, fakeStreamObserver)
//
//        //verify
//        verify(eventBus, times(1)).post(any<StatusUpdateEvent>())
//        verify(modelService, times(1)).addNewSim(check {
//            Assertions.assertEquals(it.name, "TestNode1")
//            Assertions.assertEquals(it.input, fakeStreamObserver)
//        })
    }
}