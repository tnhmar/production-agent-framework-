package com.agentframework.reasoning;
import java.util.List;
public record Prompt(List<Message> messages, InferenceParameters parameters) {
    public static Prompt of(List<Message> msgs) {
        return new Prompt(msgs, InferenceParameters.defaults());
    }
    public static Prompt user(String text) {
        return of(List.of(new Message(Message.Role.USER, text)));
    }
}
