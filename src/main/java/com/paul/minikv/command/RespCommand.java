package com.paul.minikv.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class RespCommand {

    private final String name;
    private final List<byte[]> arguments;

    public RespCommand(String name, List<byte[]> arguments) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(arguments, "arguments must not be null");

        List<byte[]> copies = new ArrayList<>(arguments.size());
        for (byte[] argument : arguments) {
            Objects.requireNonNull(argument, "argument must not be null");
            copies.add(Arrays.copyOf(argument, argument.length));
        }
        this.arguments = List.copyOf(copies);
    }

    public String name() {
        return name;
    }

    public List<byte[]> arguments() {
        return arguments.stream()
                .map(argument -> Arrays.copyOf(argument, argument.length))
                .toList();
    }

    public int argumentCount() {
        return arguments.size();
    }

    public byte[] argument(int index) {
        byte[] argument = arguments.get(index);
        return Arrays.copyOf(argument, argument.length);
    }
}
