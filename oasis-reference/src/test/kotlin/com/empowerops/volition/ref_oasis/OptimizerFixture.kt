package com.empowerops.volition.ref_oasis

import com.empowerops.volition.dto.RequestQueryDTO
import com.empowerops.volition.dto.RequestRegistrationCommandDTO
import com.google.common.eventbus.EventBus
import com.nhaarman.mockitokotlin2.*
import io.grpc.stub.StreamObserver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

class OptimizerFixture {
    @Test @Disabled("pending proper testing regime from geoff!")
    fun `when optimizer ask to register a node register`() {
        //setup
        val eventBus = mock<EventBus>()
        val apiService = mock<ApiService>()
        val fakeStreamObserver = mock<StreamObserver<RequestQueryDTO>>()

        val registrationDTO = RequestRegistrationCommandDTO.newBuilder().setName("TestNode1").build()
        val optimizerEndpoint = OptimizerEndpoint(apiService)

        //act
        optimizerEndpoint.registerRequest(registrationDTO, fakeStreamObserver)

        //verify
        verify(eventBus, times(1)).post(any<StatusUpdateEvent>())
        verify(apiService, times(1)).register(
                check {
                    assertEquals(registrationDTO, it)
                },
                check {
                    assertEquals(fakeStreamObserver, it)
                }
        )
    }
}