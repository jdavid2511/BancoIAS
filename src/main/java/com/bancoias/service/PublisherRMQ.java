package com.bancoias.service;

import lombok.RequiredArgsConstructor;
import com.bancoias.api.dto.TransferRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@EnableRabbit
@RequiredArgsConstructor
public class PublisherRMQ {

    private final  Queue queue;
    private final  RabbitTemplate rabbitTemplate;
    private final  ObjectMapper objectMapper;

    public void send(TransferRequest message) {

        try {
            Object objectMessage = message.toString();

            String jsonMessage = objectMapper.writeValueAsString(objectMessage);
            rabbitTemplate.convertAndSend(queue.getName(), message);
            log.info("Mensaje enviado correctamente a RabbitMQ: {} - queue: {}", jsonMessage, queue.getName());

        } catch (Exception e){
            log.error("Error al enviar el mensaje: {}", e.getMessage());
        }
    }
}
