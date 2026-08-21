import java.util.ArrayList;
import java.util.List;

/**
 * Human-readable scenario serialization shared by export and import. The
 * format is line-oriented key/value text so files stay diff-friendly:
 *
 * <pre>
 *   # arknights pathfinding scenario v4
 *   map 12 8
 *   unit 1
 *   spawn 1.5 1.5
 *   endpoint 10.5 6.5
 *   movement GROUND
 *   speed 1.0
 *   diagonal true
 *   checkpoint MOVE 5.5 1.5
 *   unit 2
 *   spawn 2.5 2.5
 *   endpoint 8.5 6.5
 *   movement FLYING
 *   speed 2.0
 *   diagonal false
 *   terrain 7 3 BOX
 * </pre>
 *
 * Only non-OPEN terrain cells are listed. Each {@code unit <n>} line starts
 * the next route block. Version compatibility: v4 (this writer) stores route
 * points as decimal world coordinates; older files stored integer grid cells
 * and are converted to cell centers on load. Files without any unit line (v2)
 * describe a single implicit unit; bare "checkpoint x y" lines from v1 files
 * are still read as MOVE. Parsing rejects unknown or misplaced lines with
 * their line number instead of ignoring them silently.
 */
final class ScenarioCodec {
    static final int MINIMUM_DIMENSION = 2;
    static final int MAXIMUM_DIMENSION = 512;
    /** Header token that marks decimal route-point coordinates (v4 and later). */
    private static final String DECIMAL_HEADER = "# arknights pathfinding scenario v4";

    private ScenarioCodec() {
    }

    record TerrainEntry(UiCell cell, UiTerrain terrain) {
    }

    record UnitSpec(UiPoint spawn, UiPoint endpoint, List<UiCheckpoint> checkpoints,
                    UiMovementMode movementMode, float speed, boolean allowDiagonalMove) {
    }

    record Scenario(int width, int height, List<UnitSpec> units, List<TerrainEntry> terrain) {
    }

    static String format(int width, int height, List<UiTerrain> terrain, List<UnitSpec> units) {
        StringBuilder text = new StringBuilder();
        text.append(DECIMAL_HEADER).append('\n');
        text.append("map ").append(width).append(' ').append(height).append('\n');
        for (int unitIndex = 0; unitIndex < units.size(); unitIndex++) {
            UnitSpec unit = units.get(unitIndex);
            text.append("unit ").append(unitIndex + 1).append('\n');
            text.append("spawn ").append(Float.toString(unit.spawn().x())).append(' ')
                    .append(Float.toString(unit.spawn().y())).append('\n');
            text.append("endpoint ").append(Float.toString(unit.endpoint().x())).append(' ')
                    .append(Float.toString(unit.endpoint().y())).append('\n');
            text.append("movement ").append(unit.movementMode().name()).append('\n');
            text.append("speed ").append(Float.toString(unit.speed())).append('\n');
            text.append("diagonal ").append(unit.allowDiagonalMove()).append('\n');
            for (UiCheckpoint checkpoint : unit.checkpoints()) {
                text.append("checkpoint ").append(checkpoint.type().name());
                if (checkpoint.point() != null) {
                    text.append(' ').append(Float.toString(checkpoint.point().x())).append(' ')
                            .append(Float.toString(checkpoint.point().y()));
                } else if (checkpoint.type().usesSeconds()) {
                    text.append(' ').append(Float.toString(checkpoint.value()));
                } else if (checkpoint.type().usesArea()) {
                    text.append(' ').append(checkpoint.area());
                }
                text.append('\n');
            }
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
        // Files saved with a UTF-8 BOM would otherwise fail on the first line.
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        Integer width = null;
        Integer height = null;
        List<UnitSpec> units = new ArrayList<>();
        List<TerrainEntry> terrain = new ArrayList<>();
        UnitBuilder current = null;

        String[] lines = text.split("\\R", -1);
        // Without the v4 header, route points are integer grid cells and load
        // as their centers; terrain is cell-based in every version.
        boolean decimalPoints = false;
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#")) {
                decimalPoints = line.startsWith(DECIMAL_HEADER);
                break;
            }
            break;
        }
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
            if (current == null && isUnitScoped(directive)) {
                // v2 files carry unit-scoped lines without a 'unit' header:
                // they describe a single implicit unit.
                current = new UnitBuilder();
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
                case "unit" -> {
                    requireParts(parts, 2, "unit <number>", lineNumber);
                    if (current != null) {
                        units.add(current.build(lineNumber));
                    }
                    int unitNumber = parseInteger(parts[1], "Unit number", lineNumber);
                    if (unitNumber != units.size() + 1) {
                        throw new IllegalArgumentException("Unit numbers must be sequential; expected "
                                + (units.size() + 1) + " at line " + lineNumber);
                    }
                    current = new UnitBuilder();
                }
                case "spawn" -> {
                    requireParts(parts, 3, "spawn <x> <y>", lineNumber);
                    if (current.spawn != null) {
                        throw duplicate("spawn", lineNumber);
                    }
                    current.spawn = parsePoint(parts[1], parts[2], width, height, decimalPoints,
                            lineNumber);
                }
                case "endpoint" -> {
                    requireParts(parts, 3, "endpoint <x> <y>", lineNumber);
                    if (current.endpoint != null) {
                        throw duplicate("endpoint", lineNumber);
                    }
                    current.endpoint = parsePoint(parts[1], parts[2], width, height, decimalPoints,
                            lineNumber);
                }
                case "movement" -> {
                    requireParts(parts, 2, "movement GROUND|FLYING", lineNumber);
                    if (current.movementMode != null) {
                        throw duplicate("movement", lineNumber);
                    }
                    current.movementMode = parseMovement(parts[1], lineNumber);
                }
                case "speed" -> {
                    requireParts(parts, 2, "speed <float>", lineNumber);
                    if (current.speed != null) {
                        throw duplicate("speed", lineNumber);
                    }
                    float value = parseFloat(parts[1], lineNumber);
                    if (!Float.isFinite(value) || value < 0.1f) {
                        throw new IllegalArgumentException(
                                "Movement speed must be a finite number of at least 0.1 at line "
                                        + lineNumber);
                    }
                    current.speed = value;
                }
                case "diagonal" -> {
                    requireParts(parts, 2, "diagonal true|false", lineNumber);
                    if (current.diagonal != null) {
                        throw duplicate("diagonal", lineNumber);
                    }
                    if (!parts[1].equals("true") && !parts[1].equals("false")) {
                        throw new IllegalArgumentException(
                                "diagonal must be true or false at line " + lineNumber);
                    }
                    current.diagonal = Boolean.parseBoolean(parts[1]);
                }
                case "checkpoint" -> {
                    UiCheckpoint checkpoint;
                    if (parts.length == 3 && isInteger(parts[1]) && isInteger(parts[2])) {
                        // Legacy v1 lines carried bare cell coordinates and meant MOVE.
                        checkpoint = UiCheckpoint.move(
                                parsePoint(parts[1], parts[2], width, height, decimalPoints,
                                        lineNumber));
                    } else {
                        if (parts.length < 2) {
                            throw new IllegalArgumentException(
                                    "Expected 'checkpoint <TYPE> [arguments]' at line " + lineNumber);
                        }
                        checkpoint = parseCheckpointBody(parts, width, height, decimalPoints,
                                lineNumber);
                    }
                    if (checkpoint.point() != null
                            && containsPoint(current.checkpoints, checkpoint.point())) {
                        UiCell cell = new UiCell((int) Math.floor(checkpoint.point().x()),
                                (int) Math.floor(checkpoint.point().y()));
                        throw new IllegalArgumentException("Duplicate checkpoint at cell ("
                                + cell.x() + ", " + cell.y() + ") at line " + lineNumber);
                    }
                    current.checkpoints.add(checkpoint);
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
        if (current != null) {
            units.add(current.build(lines.length + 1));
        }
        if (units.isEmpty()) {
            throw new IllegalArgumentException("Scenario requires at least one unit block");
        }
        return new Scenario(width, height, List.copyOf(units), List.copyOf(terrain));
    }

    private static boolean isUnitScoped(String directive) {
        return switch (directive) {
            case "spawn", "endpoint", "movement", "speed", "diagonal", "checkpoint" -> true;
            default -> false;
        };
    }

    /** Mutable parse buffer for one unit block; finalized into an immutable UnitSpec. */
    private static final class UnitBuilder {
        private UiPoint spawn;
        private UiPoint endpoint;
        private final List<UiCheckpoint> checkpoints = new ArrayList<>();
        private UiMovementMode movementMode;
        private Float speed;
        private Boolean diagonal;

        private UnitSpec build(int lineNumber) {
            if (spawn == null || endpoint == null) {
                throw new IllegalArgumentException("A unit needs spawn and endpoint points (near line "
                        + lineNumber + ")");
            }
            if (movementMode == null || speed == null || diagonal == null) {
                throw new IllegalArgumentException("A unit needs movement, speed, and diagonal values (near line "
                        + lineNumber + ")");
            }
            return new UnitSpec(spawn, endpoint, List.copyOf(checkpoints), movementMode, speed, diagonal);
        }
    }

    private static UiCheckpoint parseCheckpointBody(String[] parts, int width, int height,
                                                    boolean decimalPoints, int lineNumber) {
        UiCheckpointType type = parseCheckpointType(parts[1], lineNumber);
        if (type.hasPoint()) {
            requireParts(parts, 4, "checkpoint " + type.name() + " <x> <y>", lineNumber);
            return type.create(parsePoint(parts[2], parts[3], width, height, decimalPoints,
                    lineNumber));
        }
        if (type.usesSeconds()) {
            requireParts(parts, 3, "checkpoint " + type.name() + " <seconds>", lineNumber);
            float seconds = parseFloat(parts[2], lineNumber);
            if (!Float.isFinite(seconds) || seconds < 0f) {
                throw new IllegalArgumentException(
                        "Checkpoint seconds must be finite and non-negative at line " + lineNumber);
            }
            return type.create(null, seconds, 0);
        }
        if (type.usesArea()) {
            requireParts(parts, 3, "checkpoint WAIT_BOSSRUSH_WAVE <area>", lineNumber);
            int area = parseInteger(parts[2], "Area", lineNumber);
            if (area < 0) {
                throw new IllegalArgumentException(
                        "Checkpoint area must be non-negative at line " + lineNumber);
            }
            return type.create(null, 0f, area);
        }
        requireParts(parts, 2, "checkpoint " + type.name(), lineNumber);
        return type.create(null);
    }

    private static UiCheckpointType parseCheckpointType(String token, int lineNumber) {
        for (UiCheckpointType value : UiCheckpointType.values()) {
            if (value.name().equals(token)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown checkpoint type '" + token + "' at line " + lineNumber);
    }

    private static boolean containsPoint(List<UiCheckpoint> checkpoints, UiPoint point) {
        UiCell cell = new UiCell((int) Math.floor(point.x()), (int) Math.floor(point.y()));
        for (UiCheckpoint checkpoint : checkpoints) {
            if (checkpoint.point() == null) {
                continue;
            }
            UiCell other = new UiCell((int) Math.floor(checkpoint.point().x()),
                    (int) Math.floor(checkpoint.point().y()));
            if (cell.equals(other)) {
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

    /**
     * Route points: v4 files carry decimal world coordinates; older files
     * carry integer grid cells which map to cell centers.
     */
    private static UiPoint parsePoint(String xToken, String yToken, int width, int height,
                                      boolean decimalPoints, int lineNumber) {
        if (!decimalPoints) {
            UiCell cell = parseCell(xToken, yToken, width, height, lineNumber);
            return cell.center();
        }
        float x = parseCoordinate(xToken, lineNumber);
        float y = parseCoordinate(yToken, lineNumber);
        if (x < 0f || x > width || y < 0f || y > height) {
            throw new IllegalArgumentException(
                    "Point (" + x + ", " + y + ") is outside the map at line " + lineNumber);
        }
        return new UiPoint(x, y);
    }

    private static float parseCoordinate(String token, int lineNumber) {
        try {
            float value = Float.parseFloat(token);
            if (!Float.isFinite(value)) {
                throw new NumberFormatException("not finite");
            }
            return value;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                    "Coordinate '" + token + "' is not a finite number at line " + lineNumber, error);
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
