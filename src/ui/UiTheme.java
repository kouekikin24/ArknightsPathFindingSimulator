import java.awt.Color;

/**
 * Named palette for the whole workbench. LIGHT reproduces the original colors;
 * DARK is a low-glare night variant. Components read their colors from the
 * active theme and repaint when it changes.
 */
public record UiTheme(
        Color windowBackground, Color panelBackground, Color border, Color valueText, Color labelText,
        Color canvasBackground, Color gridLine, Color path, Color trajectory, Color rejection,
        Color entity, Color cursor, Color spawn, Color endpoint, Color checkpoint,
        Color terrainOpen, Color terrainBox, Color terrainPit, Color terrainWall,
        Color[] unitColors, Color[] unitTrajectoryColors) {

    public static final UiTheme LIGHT = new UiTheme(
            new Color(242, 246, 243), new Color(250, 252, 250), new Color(196, 207, 200),
            new Color(31, 48, 41), new Color(94, 111, 101),
            new Color(234, 240, 235), new Color(191, 204, 196), new Color(68, 135, 116),
            new Color(198, 57, 46), new Color(214, 48, 44),
            new Color(222, 89, 64), new Color(38, 50, 45), new Color(43, 137, 91),
            new Color(184, 61, 67), new Color(45, 122, 171),
            new Color(249, 252, 249), new Color(194, 107, 84), new Color(223, 177, 62),
            new Color(81, 97, 91),
            new Color[]{new Color(222, 89, 64), new Color(64, 132, 222),
                    new Color(64, 190, 96), new Color(205, 140, 60)},
            new Color[]{new Color(198, 57, 46), new Color(52, 101, 192),
                    new Color(40, 148, 82), new Color(171, 96, 40)});

    public static final UiTheme DARK = new UiTheme(
            new Color(24, 28, 26), new Color(33, 38, 36), new Color(60, 68, 64),
            new Color(226, 233, 229), new Color(150, 161, 155),
            new Color(20, 24, 22), new Color(48, 56, 52), new Color(96, 170, 144),
            new Color(226, 108, 94), new Color(226, 84, 78),
            new Color(233, 122, 96), new Color(201, 211, 205), new Color(88, 180, 130),
            new Color(216, 108, 114), new Color(98, 162, 210),
            new Color(30, 35, 33), new Color(152, 86, 68), new Color(180, 144, 54),
            new Color(72, 84, 79),
            new Color[]{new Color(233, 122, 96), new Color(96, 158, 235),
                    new Color(96, 205, 122), new Color(222, 162, 84)},
            new Color[]{new Color(226, 108, 94), new Color(88, 132, 214),
                    new Color(74, 172, 108), new Color(196, 122, 62)});

    public Color terrain(UiTerrain terrain) {
        return switch (terrain) {
            case OPEN -> terrainOpen;
            case BOX -> terrainBox;
            case PIT -> terrainPit;
            case WALL -> terrainWall;
        };
    }
}
