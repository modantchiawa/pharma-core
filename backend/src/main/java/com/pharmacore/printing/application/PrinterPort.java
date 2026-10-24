package com.pharmacore.printing.application;

/** Outbound port: delivers a raw ESC/POS payload to one physical printer. */
public interface PrinterPort {

    /**
     * @throws PrinterUnavailableException when the printer cannot be reached or refuses the job
     */
    void print(byte[] payload);

    final class PrinterUnavailableException extends RuntimeException {

        public PrinterUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
