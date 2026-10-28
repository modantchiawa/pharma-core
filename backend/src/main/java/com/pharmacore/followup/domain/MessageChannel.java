package com.pharmacore.followup.domain;

/** How a message reaches the patient: a WhatsApp link opened by the team, or an SMS sent by the gateway. */
public enum MessageChannel {
    WHATSAPP,
    SMS
}
