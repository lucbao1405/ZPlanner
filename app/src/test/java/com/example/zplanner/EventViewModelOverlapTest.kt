package com.example.zplanner

import com.example.zplanner.data.*
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class EventViewModelOverlapTest {
    @Before fun setMainDispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun resetMainDispatcher() { Dispatchers.resetMain() }
    private lateinit var api: ControlledApi
    private lateinit var vm: EventViewModel
    private val event = ApiEvent(title = "demo", startAt = "2026-11-20T10:00:00.000Z", endAt = "2026-11-20T11:00:00.000Z")

    private fun setUp() {
        api = ControlledApi()
        val token = mock(TokenStore::class.java)
        `when`(token.read()).thenReturn("unit-test-session")
        vm = EventViewModel(api, token)
    }

    @Test fun first409RetainsPendingUntilCancel() = runBlocking {
        setUp(); api.enqueue(Response.error(409, errorBody("EVENT_OVERLAP")))
        assertFalse(vm.saveAwait(event)); assertEquals(listOf(false), api.confirmFlags)
        vm.clearOverlap(); assertFalse(vm.confirmAwait()); assertEquals(1, api.confirmFlags.size)
    }

    @Test fun cancelClearsPendingWithoutConfirmedRequest() = runBlocking {
        setUp(); api.enqueue(Response.error(409, errorBody("EVENT_OVERLAP"))); vm.saveAwait(event)
        vm.clearOverlap(); assertFalse(vm.confirmAwait()); assertEquals(listOf(false), api.confirmFlags)
    }

    @Test fun repeatedConfirmWhileRequestInFlightSendsOnlyOnce() = runBlocking {
        setUp(); api.enqueue(Response.error(409, errorBody("EVENT_OVERLAP"))); vm.saveAwait(event)
        val gate = CompletableDeferred<Response<EventResponse>>(); api.enqueueDeferred(gate)
        val result = CompletableDeferred<Boolean>(); vm.confirmOverlap { result.complete(it) }
        vm.confirmOverlap { error("duplicate callback") }
        assertEquals(listOf(false, true), api.confirmFlags)
        gate.complete(Response.success(EventResponse(event))); assertTrue(result.await())
    }

    @Test fun confirmSuccessClearsPending() = runBlocking {
        setUp(); api.enqueue(Response.error(409, errorBody("EVENT_OVERLAP"))); vm.saveAwait(event)
        api.enqueue(Response.success(EventResponse(event))); assertTrue(vm.confirmAwait()); assertFalse(vm.confirmAwait())
    }

    @Test fun networkFailureAllowsFreshSaveAndOverlap() = runBlocking {
        setUp(); api.enqueue(Response.error(409, errorBody("EVENT_OVERLAP"))); vm.saveAwait(event)
        api.enqueueFailure(IOException("offline")); assertFalse(vm.confirmAwait())
        api.enqueue(Response.error(409, errorBody("EVENT_OVERLAP"))); assertFalse(vm.saveAwait(event))
        assertEquals(listOf(false, true, false), api.confirmFlags)
    }

    @Test fun http500AllowsFreshSaveAndOverlap() = runBlocking {
        setUp(); api.enqueue(Response.error(409, errorBody("EVENT_OVERLAP"))); vm.saveAwait(event)
        api.enqueue(Response.error(500, errorBody("SERVER_ERROR"))); assertFalse(vm.confirmAwait())
        api.enqueue(Response.error(409, errorBody("EVENT_OVERLAP"))); assertFalse(vm.saveAwait(event))
        assertEquals(listOf(false, true, false), api.confirmFlags)
    }

    @Test fun repeated409CanBeConfirmedAgain() = runBlocking {
        setUp(); api.enqueue(Response.error(409, errorBody("EVENT_OVERLAP"))); vm.saveAwait(event)
        api.enqueue(Response.error(409, errorBody("EVENT_OVERLAP"))); assertFalse(vm.confirmAwait())
        api.enqueue(Response.success(EventResponse(event))); assertTrue(vm.confirmAwait())
    }

    @Test fun http401ClearsPendingWithoutSuccess() = runBlocking {
        setUp(); api.enqueue(Response.error(409, errorBody("EVENT_OVERLAP"))); vm.saveAwait(event)
        api.enqueue(Response.error(401, errorBody("UNAUTHORIZED"))); assertFalse(vm.confirmAwait())
        assertTrue(vm.sessionExpired.value); assertFalse(vm.confirmAwait())
    }

    @Test fun clearDataIgnoresLateConfirmResponse() = runBlocking {
        setUp(); api.enqueue(Response.error(409, errorBody("EVENT_OVERLAP"))); vm.saveAwait(event)
        val gate = CompletableDeferred<Response<EventResponse>>(); api.enqueueDeferred(gate)
        val result = CompletableDeferred<Boolean>(); vm.confirmOverlap { result.complete(it) }
        vm.clearData(); gate.complete(Response.success(EventResponse(event)))
        assertFalse(result.await()); assertFalse(vm.confirmAwait())
    }

    private suspend fun EventViewModel.saveAwait(value: ApiEvent): Boolean =
        kotlinx.coroutines.suspendCancellableCoroutine { c -> save(value) { c.resume(it, null) } }
    private suspend fun EventViewModel.confirmAwait(): Boolean =
        kotlinx.coroutines.suspendCancellableCoroutine { c -> confirmOverlap { c.resume(it, null) } }

    private class ControlledApi : ZPlannerApi {
        val confirmFlags = CopyOnWriteArrayList<Boolean>()
        private val responses = ConcurrentLinkedQueue<Any>()
        fun enqueue(response: Response<EventResponse>) { responses.add(response) }
        fun enqueueFailure(error: Throwable) { responses.add(error) }
        fun enqueueDeferred(response: CompletableDeferred<Response<EventResponse>>) { responses.add(response) }
        override suspend fun createEvent(event: ApiEventRequest): Response<EventResponse> {
            confirmFlags.add(event.confirmOverlap)
            return when (val result = responses.remove() ?: error("No queued response")) {
                is CompletableDeferred<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    (result as CompletableDeferred<Response<EventResponse>>).await()
                }
                is Throwable -> throw result
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    result as Response<EventResponse>
                }
            }
        }
        override suspend fun updateEvent(id: Long, event: ApiEventRequest) = createEvent(event)
        override suspend fun events(from: String, to: String) = Response.success(EventsResponse())
        override suspend fun reminderEvents() = Response.success(EventsResponse())
        override suspend fun register(credentials: Credentials): Response<AuthResponse> = unused()
        override suspend fun login(credentials: Credentials): Response<AuthResponse> = unused()
        override suspend fun requestResetCode(request: ResetCodeRequest): Response<ResetMessage> = unused()
        override suspend fun confirmPasswordReset(request: ResetPasswordRequest): Response<ResetMessage> = unused()
        override suspend fun google(request: GoogleAuthRequest): Response<AuthResponse> = unused()
        override suspend fun linkGoogle(request: GoogleLinkRequest): Response<Unit> = unused()
        override suspend fun logout(authorization: String) = Response.success(Unit)
        override suspend fun tasks(date: String): Response<TasksResponse> = unused()
        override suspend fun reminderTasks(startDate: String, endDate: String): Response<TasksResponse> = unused()
        override suspend fun create(task: ApiTask): Response<TaskResponse> = unused()
        override suspend fun update(id: Long, task: ApiTask): Response<TaskResponse> = unused()
        override suspend fun delete(id: Long) = Response.success(Unit)
        override suspend fun deleteEvent(id: Long) = Response.success(Unit)
        private fun <T> unused(): Response<T> = throw UnsupportedOperationException("unused")
    }

    private fun errorBody(code: String) = okhttp3.ResponseBody.create(null, "{\"error\":{\"code\":\"$code\",\"message\":\"test\",\"overlaps\":[]}}")
}
