package com.eiclaude.snoop;

import androidx.wear.protolayout.ActionBuilders;
import androidx.wear.protolayout.ColorBuilders;
import androidx.wear.protolayout.DimensionBuilders;
import androidx.wear.protolayout.LayoutElementBuilders;
import androidx.wear.protolayout.ModifiersBuilders;
import androidx.wear.protolayout.ResourceBuilders;
import androidx.wear.protolayout.TimelineBuilders;
import androidx.wear.tiles.RequestBuilders;
import androidx.wear.tiles.TileBuilders;
import androidx.wear.tiles.TileService;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

/** Bloco (tile): o rosto do Snoop, uma frase do momento e o botão "Conversar". */
public class SnoopTile extends TileService {

    private static final String RESOURCES_VERSION = "1";
    private static final String IMG_FACE = "rosto";

    private static final int CARAMEL = 0xFFD88C43;
    private static final int INK = 0xFFF5F1EC;
    private static final int DARK = 0xFF1E1A17;

    @Override
    protected ListenableFuture<TileBuilders.Tile> onTileRequest(RequestBuilders.TileRequest request) {
        ModifiersBuilders.Clickable open = new ModifiersBuilders.Clickable.Builder()
                .setId("conversar")
                .setOnClick(new ActionBuilders.LaunchAction.Builder()
                        .setAndroidActivity(new ActionBuilders.AndroidActivity.Builder()
                                .setPackageName(getPackageName())
                                .setClassName(MainActivity.class.getName())
                                .build())
                        .build())
                .build();

        LayoutElementBuilders.Box button = new LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.dp(110))
                .setHeight(DimensionBuilders.dp(36))
                .setModifiers(new ModifiersBuilders.Modifiers.Builder()
                        .setBackground(new ModifiersBuilders.Background.Builder()
                                .setColor(ColorBuilders.argb(CARAMEL))
                                .setCorner(new ModifiersBuilders.Corner.Builder()
                                        .setRadius(DimensionBuilders.dp(18))
                                        .build())
                                .build())
                        .setClickable(open)
                        .build())
                .addContent(text("Conversar", 15, DARK))
                .build();

        LayoutElementBuilders.Column column = new LayoutElementBuilders.Column.Builder()
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .addContent(new LayoutElementBuilders.Image.Builder()
                        .setResourceId(IMG_FACE)
                        .setWidth(DimensionBuilders.dp(78))
                        .setHeight(DimensionBuilders.dp(78))
                        .build())
                .addContent(spacer(4))
                .addContent(text(status(), 13, INK))
                .addContent(spacer(8))
                .addContent(button)
                .build();

        LayoutElementBuilders.Box root = new LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.expand())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .setModifiers(new ModifiersBuilders.Modifiers.Builder().setClickable(open).build())
                .addContent(column)
                .build();

        TileBuilders.Tile tile = new TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setFreshnessIntervalMillis(15L * 60 * 1000)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
                .build();
        return Futures.immediateFuture(tile);
    }

    /** Frase curta do momento. */
    private String status() {
        if (!Prefs.hasKey(this)) return "Falta configurar";
        long now = System.currentTimeMillis();
        if (Prefs.inQuiet(this, now)) return "Zzz... descansando";
        long idle = Moves.idleMinutes(this, now);
        int limit = Prefs.idleMinutes(this);
        if (Pet.treatsAvailable(this, now) > 0 && Pet.belly(this, now) < 60) return "Tem petisco pra mim?";
        if (Pet.belly(this, now) < 25) return "Tô com fome...";
        if (limit > 0 && idle >= 0 && idle >= limit * 3L / 4) return "Bora dar uma volta?";
        if (Shift.enabled(this) && Shift.isOff(this, Shift.date(now))) return "Hoje é folga!";
        return "Tô aqui, " + Prefs.name(this) + "!";
    }

    @Override
    protected ListenableFuture<ResourceBuilders.Resources> onTileResourcesRequest(
            RequestBuilders.ResourcesRequest request) {
        ResourceBuilders.Resources resources = new ResourceBuilders.Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .addIdToImageMapping(IMG_FACE, new ResourceBuilders.ImageResource.Builder()
                        .setAndroidResourceByResId(new ResourceBuilders.AndroidImageResourceByResId.Builder()
                                .setResourceId(R.drawable.snoop_face)
                                .build())
                        .build())
                .build();
        return Futures.immediateFuture(resources);
    }

    private static LayoutElementBuilders.Spacer spacer(int dp) {
        return new LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(dp)).build();
    }

    private static LayoutElementBuilders.Text text(String s, float sp, int color) {
        return new LayoutElementBuilders.Text.Builder()
                .setText(s)
                .setMaxLines(2)
                .setFontStyle(new LayoutElementBuilders.FontStyle.Builder()
                        .setSize(DimensionBuilders.sp(sp))
                        .setColor(ColorBuilders.argb(color))
                        .build())
                .build();
    }
}
