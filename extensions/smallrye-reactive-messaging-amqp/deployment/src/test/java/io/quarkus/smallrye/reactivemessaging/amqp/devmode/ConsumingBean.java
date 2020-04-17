package io.quarkus.smallrye.reactivemessaging.amqp.devmode;

import javax.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.reactive.messaging.Incoming;

@ApplicationScoped
public class ConsumingBean {

    volatile int last = -1;

    @Incoming("in")
    public void consume(int content) {
        System.out.println("Consumer got " + content);
        last = content;
    }

    public int get() {
        return last;
    }

}
