package sh.harold.kinetics.api;

import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;

public record InteractionEvent(
        Player player,
        BodyId body,
        Vec3 point,
        Vec3 normal,
        EquipmentSlot hand,
        InteractionAction action
) {
    public InteractionEvent {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(point, "point");
        Objects.requireNonNull(normal, "normal");
        Objects.requireNonNull(hand, "hand");
        Objects.requireNonNull(action, "action");
    }
}
