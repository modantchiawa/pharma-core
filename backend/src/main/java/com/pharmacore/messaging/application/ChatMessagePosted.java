package com.pharmacore.messaging.application;

/** Broadcast to STOMP subscribers once the message is committed. */
public record ChatMessagePosted(ChatService.MessageView message) {
}
