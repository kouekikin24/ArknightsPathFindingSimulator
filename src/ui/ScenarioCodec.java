import java.util.ArrayList;
import java.util.List;

/**
 * Human-readable scenario serialization shared by export and import. The
 * format is line-oriented key/value text so files stay diff-friendly:
 *
 * <pre>
 *   # arknights pathfinding scenario v2
 *   map 12 8
 *   spawn 1 1
 *   endpoint 10 6
 *   movement GROUND
 *   speed 1.0
 *   diagonal true
 *   checkpoint MOVE 5 1
 *   checkpoint WAIT_FOR_SECONDS 2.5
 *   checkpoint DISAPPEAR
 *   terrain 7 3 BOX
 * </pre>
 *
 * Only non-OPEN terrain cells are listed. Checkpoint lines carry the type
 * name plus its cell, seconds, or area argument; bare "checkpoint x y" lines
 * from v1 files are still read as MOVE. Parsing rejects unknown or misplaced
 * lines with their line number instead of ignoring them silently.
 */
final class ScenarioCodec {
    static final int MINIMUM_DIMENSION = 2;
    static final int MAXIMUM_DIMENSION = 512;

    private ScenarioCodec() {
    }

    record TerrainEntry(UiCell cell, UiTerrain terrain) {
    }

    record Scenario(int width, int height, UiCell spawn, UiCell endpoint,
                    List<UiCheckpoint> checkpoints, UiMovementMode movementMode,
                    float speed, boolean allowDiagonalMove,
                    List<TerrainEntry> terrain) {
    }

    static String format(int width, int height, List<UiTerrain> terrain, UiCell spawn,
                         UiCell endpoint, List<UiCheckpoint> checkpoints, UiMovementMode movementMode,
                         float speed, boolean allowDiagonalMove) {
        StringBuilder text = new StringBuilder();
        text.append("# arknights pathfinding scenario v2\n");
        text.append("map ").append(width).append(' ').append(height).append('\n');
        text.append("spawn ").append(spawn.x()).append(' ').append(spawn.y()).append('\n');
        text.append("endpoint ").append(endpoint.x()).append(' ').append(endpoint.y()).append('\n');
        text.append("movement ").append(movementMode.name()).append('\n');
        text.append("speed ").append(Float.toString(speed)).append('\n');
        text.append("diagonal ").append(allowDiagonalMove).append('\n');
        for (UiCheckpoint checkpoint : checkpoints) {
            text.append("checkpoint ").append(checkpoint.type().name());
            if (checkpoint.cell() != null) {
                text.append(' ').append(checkpoint.cell().x()).append(' ')
                        .append(checkpoint.cell().y());
            } else if (checkpoint.type().usesSeconds()) {
                text.append(' ').append(Float.toString(checkpoint.value()));
            } else if (checkpoint.type().usesArea()) {
                text.append(' ').append(checkpoint.area());
            }
            text.append('\n');
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                UiTerrain value = terrain.get(y * width + x);
                if (value != UiTerrain.OPEN) {
                    text.append("terrain ").append(x).append(' ').append(y).append(' ')
                            .append(value.name()).append('\n');
                }
            }
        }
        return text.toString();
    }

    static Scenario parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Scenario text is required");
        }
        Integer width = null;
        Integer height = null;
        UiCell spawn = null;
        UiCell endpoint = null;
        UiMovementMode movementMode = null;
        Float speed = null;
        Boolean diagonal = null;
        List<UiCheckpoint> checkpoints = new ArrayList<>();
        List<TerrainEntry> terrain = new ArrayList<>();

        String[] lines = text.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index].strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\s+");
            String directive = parts[0];
            int lineNumber = index + 1;
            if (!directive.equals("map") && width == null) {
                throw new IllegalArgumentException("'" + directive + "' at line " + lineNumber
                        + " must appear after 'map <width> <height>'");
            }
            switch (directive) {
                case "map" -> {
                    requireParts(parts, 3, "map <width> <height>", lineNumber);
                    if (width != null) {
                        throw duplicate("map", lineNumber);
                    }
                    width = parseDimension(parts[1], lineNumber);
                    height = parseDimension(parts[2], lineNumber);
                }
                case "spawn" -> {
                    requireParts(parts, 3, "spawn <x> <y>", lineNumber);
                    if (spawn != null) {
                        throw duplicate("spawn", lineNumber);
                    }
                    spawn = parseCell(parts[1], parts[2], width, height, lineNumber);
                }
                case "endpoint" -> {
                    requireParts(parts, 3, "endpoint <x> <y>", lineNumber);
                    if (endpoint != null) {
                        throw duplicate("endpoint", lineNumber);
                    }
                    endpoint = parseCell(parts[1], parts[2], width, height, lineNumber);
                }
                case "movement" -> {
                    requireParts(parts, 2, "movement GROUND|FLYING", lineNumber);
                    if (movementMode != null) {
                        throw duplicate("movement", lineNumber);
                    }
                    movementMode = parseMovement(parts[1], lineNumber);
                }
                case "speed" -> {
                    requireParts(parts, 2, "speed <float>", lineNumber);
                    if (speed != null) {
                        throw duplicate("speed", lineNumber);
                    }
                    float value = parseFloat(parts[1], lineNumber);
                    if (!Float.isFinite(value) || value < 0.1f) {
                        throw new IllegalArgumentException(
                                "Movement speed must be a finite number of at least 0.1 at line "
                                        + lineNumber);
                    }
                    speed = value;
                }
                case "diagonal" -> {
                    requireParts(parts, 2, "diagonal true|false", lineNumber);
                    if (diagonal != null) {
                        throw duplicate("diagonal", lineNumber);
                    }
                    if (!parts[1].equals("true") && !parts[1].equals("false")) {
                        throw new IllegalArgumentException(
                                "diagonal must be true or false at line " + lineNumber);
                    }
                    diagonal = Boolean.parseBoolean(parts[1]);
                }
                case "checkpoint" -> {
                    UiCheckpoint checkpoint;
                    if (parts.length == 3 && isInteger(parts[1]) && isInteger(parts[2])) {
                        // Legacy v1 lines carried bare coordinates and meant MOVE.
                        checkpoint = UiCheckpoint.move(parseCell(parts[1], parts[2], width, height, lineNumber));
                    } else {
                        if (parts.length < 2) {
                            throw new IllegalArgumentException(
                                    "Expected 'checkpoint <TYPE> [arguments]' at line " + lineNumber);
                        }
                        checkpoint = parseCheckpointBody(parts, width, height, lineNumber);
                    }
                    if (checkpoint.cell() != null && containsCell(checkpoints, checkpoint.cell())) {
                        throw new IllegalArgumentException("Duplicate checkpoint at cell ("
                                + checkpoint.cell().x() + ", " + checkpoint.cell().y()
                                + ") at line " + lineNumber);
                    }
                    checkpoints.add(checkpoint);
                }
                case "terrain" -> {
                    requireParts(parts, 4, "terrain <x> <y> OPEN|BOX|PIT|WALL", lineNumber);
                    UiCell cell = parseCell(parts[1], parts[2], width, height, lineNumber);
                    terrain.add(new TerrainEntry(cell, parseTerrain(parts[3], lineNumber)));
                }
                default -> throw new IllegalArgumentException(
                        "Unknown directive '" + directive + "' at line " + lineNumber);
            }
        }
        if (width == null) {
            throw new IllegalArgumentException("Scenario requires a 'map <width> <height>' line");
        }
        if (spawn == null || endpoint == null) {
            throw new IllegalArgumentException("Scenario requires spawn and endpoint cells");
        }
        if (movementMode == null || speed == null || diagonal == null) {
            throw new IllegalArgumentException(
                    "Scenario requires movement, speed, and diagonal values");
        }
        return new Scenario(width, height, spawn, endpoint, List.copyOf(checkpoints),
                movementMode, speed, diagonal, List.copyOf(terrain));
    }

    private static UiCheckpoint parseCheckpointBody(String[] parts, int width, int height,
                                                    int lineNumber) {
        UiCheckpointType type = parseCheckpointType(parts[1], lineNumber);
        return switch (type) {
            case MOVE, PATROL_MOVE, APPEAR_AT_POS -> {
                requireParts(parts, 4, "checkpoint " + type.name() + " <x> <y>", lineNumber);
                UiCell cell = parseCell(parts[2], parts[3], width, height, lineNumber);
                yield switch (type) {
                    case MOVE -> UiCheckpoint.move(cell);
                    case PATROL_MOVE -> UiCheckpoint.patrolMove(cell);
                    default -> UiCheckpoint.appearAt(cell);
                };
            }
            case WAIT_FOR_SECONDS, WAIT_FOR_PLAY_TIME, WAIT_CURRENT_FRAGMENT_TIME,
                    WAIT_CURRENT_WAVE_TIME -> {
                requireParts(parts, 3, "checkpoint " + type.name() + " <seconds>", lineNumber);
                float seconds = parseFloat(parts[2], lineNumber);
                if (!Float.isFinite(seconds) || seconds < 0f) {
                    throw new IllegalArgumentException(
                            "Checkpoint seconds must be finite and non-negative at line " + lineNumber);
                }
                yield switch (type) {
                    case WAIT_FOR_SECONDS -> UiCheckpoint.waitForSeconds(seconds);
                    case WAIT_FOR_PLAY_TIME -> UiCheckpoint.waitForPlayTime(seconds);
                    case WAIT_CURRENT_FRAGMENT_TIME -> UiCheckpoint.waitForFragmentTime(seconds);
                    default -> UiCheckpoint.waitForWaveTime(seconds);
                };
            }
            case WAIT_BOSSRUSH_WAVE -> {
                requireParts(parts, 3, "checkpoint WAIT_BOSSRUSH_WAVE <area>", lineNumber);
                int area = parseInteger(parts[2], "Area", lineNumber);
                if (area < 0) {
                    throw new IllegalArgumentException(
                            "Checkpoint area must be non-negative at line " + lineNumber);
                }
                yield UiCheckpoint.waitForBossRushArea(area);
            }
            case DISAPPEAR, ALERT -> {
                requireParts(parts, 2, "checkpoint " + type.name(), lineNumber);
                yield type == UiCheckpointType.DISAPPEAR
                        ? UiCheckpoint.disappear() : UiCheckpoint.alert();
            }
        };
    }

    private static UiCheckpointType parseCheckpointType(String token, int lineNumber) {
        for (UiCheckpointType value : UiCheckpointType.values()) {
            if (value.name().equals(token)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown checkpoint type '" + token + "' at line " + lineNumber);
    }

    private static boolean containsCell(List<UiCheckpoint> checkpoints, UiCell cell) {
        for (UiCheckpoint checkpoint : checkpoints) {
            if (cell.equals(checkpoint.cell())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInteger(String token) {
        try {
            Integer.parseInt(token);
            return true;
        } catch (NumberFormatException error) {
            return false;
        }
    }

    private static void requireParts(String[] parts, int count, String usage, int lineNumber) {
        if (parts.length != count) {
            throw new IllegalArgumentException("Expected '" + usage + "' at line " + lineNumber);
        }
    }

    private static IllegalArgumentException duplicate(String directive, int lineNumber) {
        return new IllegalArgumentException("Directive '" + directive
                + "' appears twice; second occurrence at line " + lineNumber);
    }

    private static int parseDimension(String token, int lineNumber) {
        int value = parseInteger(token, "Map dimension", lineNumber);
        if (value < MINIMUM_DIMENSION || value > MAXIMUM_DIMENSION) {
            throw new IllegalArgumentException("Map dimensions must be between "
                    + MINIMUM_DIMENSION + " and " + MAXIMUM_DIMENSION + " at line " + lineNumber);
        }
        return value;
    }

    private static int parseInteger(String token, String label, int lineNumber) {
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                    label + " '" + token + "' is not an integer at line " + lineNumber, error);
        }
    }

    private static UiCell parseCell(String xToken, String yToken, int width, int height, int lineNumber) {
        int x = parseInteger(xToken, "Coordinate", lineNumber);
        int y = parseInteger(yToken, "Coordinate", lineNumber);
        if (x < 0 || x >= width || y < 0 || y >= height) {
            throw new IllegalArgumentException(
                    "Cell (" + x + ", " + y + ") is outside the map at line " + lineNumber);
        }
        return new UiCell(x, y);
    }

    private static float parseFloat(String token, int lineNumber) {
        try {
            return Float.parseFloat(token);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                    "Speed '" + token + "' is not a number at line " + lineNumber, error);
        }
    }

    private static UiMovementMode parseMovement(String token, int lineNumber) {
        for (UiMovementMode value : UiMovementMode.values()) {
            if (value.name().equals(token)) {
                return value;
            }
        }
        throw new IllegalArgumentException(
                "Unknown movement mode '" + token + "' at line " + lineNumber);
    }

    private static UiTerrain parseTerrain(String token, int lineNumber) {
        for (UiTerrain value : UiTerrain.values()) {
            if (value.name().equals(token)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown terrain '" + token + "' at line " + lineNumber);
    }
}
