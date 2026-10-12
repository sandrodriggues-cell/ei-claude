package com.eiclaude.relogio;

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

/**
 * Bloco (tile) do relógio: um botão redondo "Chamar Claude". O toque abre o app do relógio,
 * que já chama o Claude no celular.
 */
public class ClaudeTileService extends TileService {

    private static final String RESOURCES_VERSION = "1";
    private static final String IMG_WAVES = "ondas";

    private static final int ACCENT = 0xFFCF6A43;
    private static final int INK = 0xFFF5F1EC;
    private static final int MUTED = 0xFFA79F96;

    @Override
    protected ListenableFuture<TileBuilders.Tile> onTileRequest(RequestBuilders.TileRequest request) {
        ModifiersBuilders.Clickable open = new ModifiersBuilders.Clickable.Builder()
                .setId("chamar")
                .setOnClick(new ActionBuilders.LaunchAction.Builder()
                        .setAndroidActivity(new ActionBuilders.AndroidActivity.Builder()
                                .setPackageName(getPackageName())
                                .setClassName(MainActivity.class.getName())
                                .build())
                        .build())
                .build();

        LayoutElementBuilders.Box button = new LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.dp(76))
                .setHeight(DimensionBuilders.dp(76))
                .setModifiers(new ModifiersBuilders.Modifiers.Builder()
                        .setBackground(new ModifiersBuilders.Background.Builder()
                                .setColor(ColorBuilders.argb(ACCENT))
                                .setCorner(new ModifiersBuilders.Corner.Builder()
                                        .setRadius(DimensionBuilders.dp(38))
                                        .build())
                                .build())
                        .setClickable(open)
                        .build())
                .addContent(new LayoutElementBuilders.Image.Builder()
                        .setResourceId(IMG_WAVES)
                        .setWidth(DimensionBuilders.dp(44))
                        .setHeight(DimensionBuilders.dp(44))
                        .build())
                .build();

        LayoutElementBuilders.Column column = new LayoutElementBuilders.Column.Builder()
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .addContent(text("Ei Claude", 13, MUTED))
                .addContent(new LayoutElementBuilders.Spacer.Builder()
                        .setHeight(DimensionBuilders.dp(8)).build())
                .addContent(button)
                .addContent(new LayoutElementBuilders.Spacer.Builder()
                        .setHeight(DimensionBuilders.dp(10)).build())
                .addContent(text("Chamar no celular", 15, INK))
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
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
                .build();
        return Futures.immediateFuture(tile);
    }

    @Override
    protected ListenableFuture<ResourceBuilders.Resources> onTileResourcesRequest(
            RequestBuilders.ResourcesRequest request) {
        ResourceBuilders.Resources resources = new ResourceBuilders.Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .addIdToImageMapping(IMG_WAVES, new ResourceBuilders.ImageResource.Builder()
                        .setAndroidResourceByResId(new ResourceBuilders.AndroidImageResourceByResId.Builder()
                                .setResourceId(R.drawable.tile_waves)
                                .build())
                        .build())
                .build();
        return Futures.immediateFuture(resources);
    }

    private static LayoutElementBuilders.Text text(String s, float sp, int color) {
        return new LayoutElementBuilders.Text.Builder()
                .setText(s)
                .setFontStyle(new LayoutElementBuilders.FontStyle.Builder()
                        .setSize(DimensionBuilders.sp(sp))
                        .setColor(ColorBuilders.argb(color))
                        .build())
                .build();
    }
}
