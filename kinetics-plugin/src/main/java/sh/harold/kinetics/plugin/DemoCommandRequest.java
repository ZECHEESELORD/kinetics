package sh.harold.kinetics.plugin;

import java.util.Locale;
import java.util.Optional;

record DemoCommandRequest(Action action, Optional<Coordinates> coordinates) {
    DemoCommandRequest {
        if (action == null || coordinates == null) {
            throw new NullPointerException();
        }
    }

    static DemoCommandRequest parse(String[] args) {
        if (args.length == 0) throw new IllegalArgumentException("Missing demo action");

        Action action;
        try {
            action = Action.valueOf(args[0].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Unknown demo action", failure);
        }

        if (action == Action.RESET || action == Action.STOP) {
            if (args.length != 1) throw new IllegalArgumentException("Action does not accept coordinates");
            return new DemoCommandRequest(action, Optional.empty());
        }
        if (args.length == 1) return new DemoCommandRequest(action, Optional.empty());
        if (args.length != 4) throw new IllegalArgumentException("Expected x y z coordinates");

        double x = coordinate(args[1]);
        double y = coordinate(args[2]);
        double z = coordinate(args[3]);
        return new DemoCommandRequest(action, Optional.of(new Coordinates(x, y, z)));
    }

    private static double coordinate(String input) {
        double value;
        try {
            value = Double.parseDouble(input);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Coordinate is not a number", failure);
        }
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Coordinate must be finite");
        return value;
    }

    enum Action {
        SAMPLER,
        SPECTACLE,
        RESET,
        STOP;

        DemoLayout.Mode mode() {
            return switch (this) {
                case SAMPLER -> DemoLayout.Mode.SAMPLER;
                case SPECTACLE -> DemoLayout.Mode.SPECTACLE;
                case RESET, STOP -> throw new IllegalStateException(name() + " has no demo mode");
            };
        }
    }

    record Coordinates(double x, double y, double z) {
    }
}
