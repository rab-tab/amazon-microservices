package com.amazon.order.event;


import com.amazon.order.entity.Order;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class OrderCreatedEvent extends ApplicationEvent {
    private final Order order;
    private final String testScenario;

    public OrderCreatedEvent(Object source, Order order, String testScenario) {
        super(source);
        this.order = order;
        this.testScenario = testScenario;
    }
}
