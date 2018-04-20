package org.phoenixframework.channel

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.phoenixframework.MessageCallback
import org.phoenixframework.PhoenixEvent
import org.phoenixframework.PhoenixRequest
import org.phoenixframework.PhoenixResponse
import org.phoenixframework.PhoenixRequestSender
import java.io.IOException
import java.util.ArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

class Channel
internal constructor(private val requestSender: PhoenixRequestSender, val topic: String, private val objectMapper: ObjectMapper) {

  private val refBindings = ConcurrentHashMap<String, MessageCallback>()
  private val eventBindings = ArrayList<EventBinding>()

  private var state = AtomicReference<ChannelState>(ChannelState.CLOSED)

  private fun pushMessage(event: String, payload: JsonNode? = null, timeout: Long? = null, callback: MessageCallback? = null) {
    val ref = requestSender.makeRef()
    val request = PhoenixRequest(topic, event, payload, ref)
    requestSender.pushMessage(request, timeout, callback)
    callback?.let {
      synchronized(refBindings) {
        refBindings[ref] = callback
      }
    }
  }

  /**
   * Initiates a org.phoenixframework.channel join event
   *
   * @return This org.phoenixframework.PhoenixRequest instance
   * @throws IllegalStateException Thrown if the org.phoenixframework.channel has already been joined
   * @throws IOException           Thrown if the join could not be sent
   */
  @Throws(IllegalStateException::class, IOException::class)
  fun join(payload: String?, callback: MessageCallback) {
    if (state.get() == ChannelState.JOINED || state.get() == ChannelState.JOINING) {
      throw IllegalStateException(
          "Tried to join multiple times. 'join' can only be invoked once per org.phoenixframework.channel")
    }
    this.state.set(ChannelState.JOINING)
    val joinPayload = objectMapper.readTree(payload)
    pushMessage(PhoenixEvent.JOIN.phxEvent, joinPayload, callback = callback)
  }

  /**
   * Triggers event signalling to all callbacks bound to the specified event.
   * Do not call this method except for testing and [Socket].
   *
   * @param triggerEvent The event name
   * @param envelope     The response's envelope relating to the event or null if not relevant.
   */
  internal fun retrieveMessage(response: PhoenixResponse) {
    when (response.event) {
      PhoenixEvent.JOIN.phxEvent -> {
        state.set(ChannelState.JOINED)
      }
      PhoenixEvent.CLOSE.phxEvent -> {
        state.set(ChannelState.CLOSED)
      }
      PhoenixEvent.ERROR.phxEvent -> {
        retrieveFailure(response = response)
      }
      // Includes org.phoenixframework.PhoenixEvent.REPLY
      else -> {
        response.ref?.let {
          trigger(it, response)
        }
        eventBindings.filter { it.event == response.event }
            .forEach { it.callback?.onMessage(response) }
      }
    }
  }

  internal fun retrieveFailure(throwable: Throwable? = null, response: PhoenixResponse? = null) {
    state.set(ChannelState.ERRORED)
    response?.event.let { event ->
      eventBindings.filter { it.event == event }
          .forEach { it.callback?.onFailure(throwable, response) }
    }
    // TODO(changhee): Rejoin org.phoenixframework.channel with timer.
  }

  private fun trigger(ref: String, response: PhoenixResponse) {
    val callback = refBindings[ref]
    when (response.responseStatus) {
      "ok" -> callback?.onMessage(response)
      else -> callback?.onFailure(response = response)
    }
    refBindings.remove(ref)
  }

  /**
   * @return true if the socket is open and the org.phoenixframework.channel has joined
   */
  private fun canPush(): Boolean {
    return this.state.get() === ChannelState.JOINED && this.requestSender.canPushMessage()
  }

  @Throws(IOException::class)
  fun leave(callback: MessageCallback) {
    pushMessage(PhoenixEvent.LEAVE.phxEvent, callback = callback)
  }

  /**
   * Unsubscribe for event notifications
   *
   * @param event The event name
   * @return The instance's self
   */
  fun off(event: String): Channel {
    synchronized(eventBindings) {
      val bindingIter = eventBindings.iterator()
      while (bindingIter.hasNext()) {
        if (bindingIter.next().event == event) {
          bindingIter.remove()
          break
        }
      }
    }
    return this
  }

  /**
   * @param event    The event name
   * @param callback The callback to be invoked with the event's message
   * @return The instance's self
   */
  fun on(event: String, callback: MessageCallback): Channel {
    synchronized(eventBindings) {
      this.eventBindings.add(EventBinding(event, callback))
    }
    return this
  }

  fun on(event: PhoenixEvent, callback: MessageCallback): Channel = on(event.phxEvent, callback)

  /**
   * Pushes a payload to be sent to the org.phoenixframework.channel
   *
   * @param event   The event name
   * @param payload The message payload
   * @param timeout The number of milliseconds to wait before triggering a timeout
   * @throws IOException           Thrown if the payload cannot be pushed
   * @throws IllegalStateException Thrown if the org.phoenixframework.channel has not yet been joined
   */
  @Throws(IOException::class)
  fun push(event: String, payload: JsonNode? = null, timeout: Long? = null, callback: MessageCallback? = null) {
    if (state.get() != ChannelState.JOINED) {
      throw IllegalStateException("Unable to push event before org.phoenixframework.channel has been joined")
    }
    pushMessage(event, payload, timeout, callback)
  }

  override fun toString(): String {
    return "org.phoenixframework.channel.Channel{" +
        "topic='" + topic + '\'' +
        ", eventBindings(" + eventBindings.size + ")=" + eventBindings +
        '}'
  }
}