package de.photographics.gnome

import de.photographics.core.editor.GradientType
import de.photographics.core.selection.SelectionOp
import de.photographics.core.selection.SelectionShape
import de.photographics.core.tools.BrushSettings
import de.photographics.core.tools.LiquifyMode
import de.photographics.core.tools.PaintTarget
import de.photographics.core.tools.ToneRange
import de.photographics.core.tr

/** Werkzeuge wie in der Android-App; [key] = Tastenkürzel (wie Photoshop), [icon] = Symbolname. */
enum class Tool(val label: String, val icon: String, val key: Char?) {
    MOVE(tr("Verschieben"), "pg-move-symbolic", 'v'),
    TRANSFORM(tr("Transformieren"), "pg-transform-symbolic", null),
    SELECT(tr("Auswahl"), "pg-select-rect-symbolic", 'm'),
    LASSO(tr("Lasso"), "pg-lasso-symbolic", 'l'),
    POLY_LASSO(tr("Polygon-Lasso"), "pg-polylasso-symbolic", 'l'),
    OBJECT_SELECT(tr("Objektauswahl"), "pg-object-select-symbolic", 'w'),
    QUICK_SELECT(tr("Schnellauswahl"), "pg-quick-select-symbolic", 'w'),
    WAND(tr("Zauberstab"), "pg-wand-symbolic", 'w'),
    CROP(tr("Freistellen"), "pg-crop-symbolic", 'c'),
    BRUSH(tr("Pinsel"), "pg-brush-symbolic", 'b'),
    PENCIL(tr("Buntstift"), "pg-pencil-symbolic", 'b'),
    ERASER(tr("Radiergummi"), "pg-eraser-symbolic", 'e'),
    BG_ERASER(tr("Hintergrund-Radiergummi"), "pg-bg-eraser-symbolic", 'e'),
    MAGIC_ERASER(tr("Magischer Radiergummi"), "pg-magic-eraser-symbolic", 'e'),
    GRADIENT(tr("Verlauf"), "pg-gradient-symbolic", 'g'),
    BUCKET(tr("Füllwerkzeug"), "pg-bucket-symbolic", 'g'),
    EYEDROPPER(tr("Pipette"), "pg-eyedropper-symbolic", 'i'),
    RULER(tr("Linealwerkzeug"), "pg-ruler-symbolic", 'i'),
    SPOT_HEAL(tr("Bereichsreparatur"), "pg-heal-symbolic", 'j'),
    HEAL(tr("Reparatur-Pinsel"), "pg-heal-symbolic", null),
    AI_REMOVE(tr("KI-Entfernen"), "pg-ai-remove-symbolic", null),
    CLONE(tr("Kopierstempel"), "pg-clone-symbolic", 's'),
    PATCH(tr("Ausbessern"), "pg-patch-symbolic", null),
    RED_EYE(tr("Rote Augen"), "pg-redeye-symbolic", null),
    BLUR(tr("Weichzeichner"), "pg-blur-symbolic", 'r'),
    SHARPEN(tr("Scharfzeichner"), "pg-sharpen-symbolic", null),
    SMUDGE(tr("Wischfinger"), "pg-smudge-symbolic", null),
    LIQUIFY(tr("Verflüssigen"), "pg-liquify-symbolic", null),
    DODGE(tr("Abwedler"), "pg-dodge-symbolic", 'o'),
    BURN(tr("Nachbelichter"), "pg-burn-symbolic", null),
    SPONGE(tr("Schwamm"), "pg-sponge-symbolic", null),
    TEXT(tr("Text"), "pg-text-symbolic", 't'),
    SHAPE_RECT(tr("Rechteck"), "pg-shape-rect-symbolic", 'u'),
    SHAPE_ELLIPSE(tr("Ellipse"), "pg-shape-ellipse-symbolic", 'u'),
    SHAPE_POLYGON(tr("Polygon"), "pg-shape-polygon-symbolic", 'u'),
    SHAPE_LINE(tr("Linienzeichner"), "pg-shape-line-symbolic", 'u'),
    PEN(tr("Zeichenstift"), "pg-pen-symbolic", 'p'),
    DIRECT_SELECT(tr("Direktauswahl"), "pg-direct-select-symbolic", 'a'),
    HAND(tr("Hand"), "pg-hand-symbolic", 'h'),
    ZOOM(tr("Zoom"), "pg-zoom-symbolic", 'z');

    val isPaintTool get() = this in PAINT_TOOLS
    val isShapeTool get() = this == SHAPE_RECT || this == SHAPE_ELLIPSE || this == SHAPE_POLYGON || this == SHAPE_LINE
    val group: ToolGroup get() = ToolGroup.entries.first { this in it.tools }

    companion object {
        val PAINT_TOOLS = setOf(BRUSH, PENCIL, ERASER, BG_ERASER, CLONE, HEAL, SPOT_HEAL, AI_REMOVE, BLUR, SHARPEN, DODGE, BURN, SPONGE, SMUDGE, LIQUIFY, RED_EYE)
    }
}

/** Gruppen der Werkzeugleiste: ein Knopf je Gruppe, das zuletzt benutzte Werkzeug steht vorn. */
enum class ToolGroup(val label: String, val tools: List<Tool>) {
    MOVE(tr("Bewegen"), listOf(Tool.MOVE, Tool.TRANSFORM)),
    SELECT(tr("Auswahl"), listOf(Tool.SELECT, Tool.LASSO, Tool.POLY_LASSO)),
    OBJECT(tr("Objektauswahl"), listOf(Tool.OBJECT_SELECT, Tool.QUICK_SELECT, Tool.WAND)),
    CROP(tr("Zuschnitt"), listOf(Tool.CROP)),
    PICK(tr("Pipette"), listOf(Tool.EYEDROPPER, Tool.RULER)),
    PAINT(tr("Malen"), listOf(Tool.BRUSH, Tool.PENCIL)),
    ERASE(tr("Radiergummi"), listOf(Tool.ERASER, Tool.BG_ERASER, Tool.MAGIC_ERASER)),
    FILL(tr("Verlauf und Füllen"), listOf(Tool.GRADIENT, Tool.BUCKET)),
    RETOUCH(tr("Retusche"), listOf(Tool.SPOT_HEAL, Tool.AI_REMOVE, Tool.HEAL, Tool.CLONE, Tool.PATCH, Tool.RED_EYE)),
    FOCUS(tr("Schärfe"), listOf(Tool.BLUR, Tool.SHARPEN, Tool.SMUDGE, Tool.LIQUIFY)),
    LIGHT(tr("Licht"), listOf(Tool.DODGE, Tool.BURN, Tool.SPONGE)),
    TEXT(tr("Text"), listOf(Tool.TEXT)),
    PATH(tr("Pfade"), listOf(Tool.PEN, Tool.DIRECT_SELECT)),
    SHAPE(tr("Formen"), listOf(Tool.SHAPE_RECT, Tool.SHAPE_ELLIPSE, Tool.SHAPE_POLYGON, Tool.SHAPE_LINE)),
    HAND(tr("Hand"), listOf(Tool.HAND)),
    ZOOM(tr("Zoom"), listOf(Tool.ZOOM)),
}

/** Seitenverhältnis beim Freistellen (Breite zu Höhe), null = frei. */
data class CropAspect(val label: String, val w: Double, val h: Double)

/** Einstellungen der Werkzeuge (nur im Hauptthread; Kopien gehen an den Engine-Thread). */
data class ToolOptions(
    val tool: Tool = Tool.BRUSH,
    val brush: BrushSettings = BrushSettings(),
    val color: Int = 0xFF000000.toInt(),
    val bgColor: Int = 0xFFFFFFFF.toInt(),
    val paintTarget: PaintTarget = PaintTarget.PIXELS,
    val selectionShape: SelectionShape = SelectionShape.RECT,
    val selectionOp: SelectionOp = SelectionOp.REPLACE,
    val feather: Float = 0f,
    val wandTolerance: Int = 32,
    val wandContiguous: Boolean = true,
    /** Füllwerkzeug und Magischer Radiergummi: Toleranz, Glätten, Deckkraft, Mischmodus. */
    val fillTolerance: Int = 32,
    val antiAlias: Boolean = true,
    val fillOpacity: Float = 1f,
    val fillBlend: de.photographics.core.render.BlendMode = de.photographics.core.render.BlendMode.NORMAL,
    /** Hintergrund-Radiergummi: Toleranz, Farbe nur einmal aufnehmen, Vordergrundfarbe schützen. */
    val bgEraserTolerance: Int = 50,
    val bgEraserOnce: Boolean = false,
    val bgEraserProtect: Boolean = false,
    /** Linealwerkzeug: gemessene Linie (x0, y0, x1, y1) in Dokumentkoordinaten. */
    val rulerLine: List<Double>? = null,
    val sampleAll: Boolean = false,
    val gradientType: GradientType = GradientType.LINEAR,
    val gradientToTransparent: Boolean = false,
    val gradientOpacity: Float = 1f,
    val toneRange: ToneRange = ToneRange.MIDTONES,
    val strength: Float = 0.5f,
    val spongeSaturate: Boolean = false,
    val liquifyMode: LiquifyMode = LiquifyMode.PUSH,
    val eyedropperToBg: Boolean = false,
    val autoSelectLayer: Boolean = false,
    val cropAspect: CropAspect? = null,
    val cropDeleteCropped: Boolean = true,
    /** Freistellen: Drehung des Bildes (Grad, im Uhrzeigersinn), Gerade-ausrichten-Linie, perspektivisch. */
    val cropAngle: Float = 0f,
    val cropStraighten: Boolean = false,
    val cropPerspective: Boolean = false,
    /** Formwerkzeuge und Zeichenstift: Formebene anlegen (sonst Arbeitspfad). */
    val shapeLayerMode: Boolean = true,
    /** Füllung mit der Vordergrundfarbe; Kontur in [strokeColor]. */
    val shapeFill: Boolean = true,
    val shapeStroke: Boolean = false,
    val strokeColor: Int = 0xFF000000.toInt(),
    val strokeWidth: Float = 3f,
    val strokeAlign: de.photographics.core.vector.ShapeStroke.Align = de.photographics.core.vector.ShapeStroke.Align.INSIDE,
    val cornerRadius: Float = 0f,
    val polygonSides: Int = 5,
    /** Stern: Einzug der inneren Ecken in Prozent (0 = kein Stern). */
    val starIndent: Int = 0,
    val lineWidth: Float = 4f,
)

val CROP_ASPECTS = listOf(
    CropAspect(tr("Original"), 0.0, 0.0), CropAspect("1 : 1", 1.0, 1.0), CropAspect("4 : 3", 4.0, 3.0), CropAspect("3 : 2", 3.0, 2.0),
    CropAspect("16 : 9", 16.0, 9.0), CropAspect("5 : 4", 5.0, 4.0), CropAspect("7 : 5", 7.0, 5.0),
)
