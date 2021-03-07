package com.simibubi.create.foundation.ponder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import org.apache.commons.lang3.mutable.MutableBoolean;
import org.lwjgl.opengl.GL11;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;
import com.simibubi.create.foundation.gui.AbstractSimiScreen;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.simibubi.create.foundation.gui.AllIcons;
import com.simibubi.create.foundation.gui.GuiGameElement;
import com.simibubi.create.foundation.gui.ScreenOpener;
import com.simibubi.create.foundation.gui.UIRenderHelper;
import com.simibubi.create.foundation.ponder.PonderScene.SceneTransform;
import com.simibubi.create.foundation.ponder.content.DebugScenes;
import com.simibubi.create.foundation.ponder.content.PonderChapter;
import com.simibubi.create.foundation.ponder.content.PonderIndex;
import com.simibubi.create.foundation.ponder.content.PonderTag;
import com.simibubi.create.foundation.ponder.content.PonderTagScreen;
import com.simibubi.create.foundation.ponder.ui.PonderButton;
import com.simibubi.create.foundation.renderState.SuperRenderTypeBuffer;
import com.simibubi.create.foundation.utility.AnimationTickHolder;
import com.simibubi.create.foundation.utility.ColorHelper;
import com.simibubi.create.foundation.utility.Iterate;
import com.simibubi.create.foundation.utility.Lang;
import com.simibubi.create.foundation.utility.LerpedFloat;
import com.simibubi.create.foundation.utility.LerpedFloat.Chaser;
import com.simibubi.create.foundation.utility.Pair;
import com.simibubi.create.foundation.utility.Pointing;

import net.minecraft.client.ClipboardHelper;
import net.minecraft.client.GameSettings;
import net.minecraft.client.MainWindow;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Direction;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.MutableBoundingBox;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.gen.feature.template.PlacementSettings;
import net.minecraft.world.gen.feature.template.Template;
import net.minecraftforge.fml.client.gui.GuiUtils;
import net.minecraftforge.registries.ForgeRegistries;

public class PonderUI extends AbstractSimiScreen {

	public static final String PONDERING = PonderLocalization.LANG_PREFIX + "pondering";
	public static final String IDENTIFY_MODE = PonderLocalization.LANG_PREFIX + "identify_mode";
	public static final String IN_CHAPTER = PonderLocalization.LANG_PREFIX + "in_chapter";

	private List<PonderScene> scenes;
	private List<PonderTag> tags;
	private List<PonderButton> tagButtons;
	private List<LerpedFloat> tagFades;
	private LerpedFloat fadeIn;
	private LerpedFloat sceneProgress;
	ItemStack stack;
	PonderChapter chapter = null;

	private boolean identifyMode;
	private ItemStack hoveredTooltipItem;
	private BlockPos hoveredBlockPos;

	private ClipboardHelper clipboardHelper;
	private BlockPos copiedBlockPos;

	private LerpedFloat lazyIndex;
	private int index = 0;

	private PonderButton left, right, scan, chap;

	public static PonderUI of(ItemStack item) {
		return new PonderUI(PonderRegistry.compile(item.getItem()
			.getRegistryName()));
	}

	public static PonderUI of(PonderChapter chapter) {
		PonderUI ui = new PonderUI(PonderRegistry.compile(chapter));
		ui.chapter = chapter;
		return ui;
	}

	public PonderUI(List<PonderScene> scenes) {
		ResourceLocation component = scenes.get(0).component;
		if (ForgeRegistries.ITEMS.containsKey(component))
			stack = new ItemStack(ForgeRegistries.ITEMS.getValue(component));
		else
			stack = new ItemStack(ForgeRegistries.BLOCKS.getValue(component));

		tags = new ArrayList<>(PonderRegistry.tags.getTags(component));
		this.scenes = scenes;
		if (scenes.isEmpty()) {
			List<PonderStoryBoardEntry> l = Collections.singletonList(new PonderStoryBoardEntry(DebugScenes::empty,
				"debug/scene_1", new ResourceLocation("minecraft", "stick")));
			scenes.addAll(PonderRegistry.compile(l));
		}
		lazyIndex = LerpedFloat.linear()
			.startWithValue(index);
		sceneProgress = LerpedFloat.linear()
			.startWithValue(0);
		fadeIn = LerpedFloat.linear()
			.startWithValue(0)
			.chase(1, .1f, Chaser.EXP);
		clipboardHelper = new ClipboardHelper();
	}

	@Override
	protected void init() {
		super.init();
		widgets.clear();

		tagButtons = new ArrayList<>();
		tagFades = new ArrayList<>();

		tags.forEach(t -> {
			int i = tagButtons.size();
			int x = 31;
			int y = 71 + i * 30;
			PonderButton b = new PonderButton(x, y, (mouseX, mouseY) -> {
				centerScalingOn(mouseX, mouseY);
				ScreenOpener.transitionTo(new PonderTagScreen(t));
			}).showing(t);

			widgets.add(b);
			tagButtons.add(b);

			LerpedFloat chase = LerpedFloat.linear()
				.startWithValue(0)
				.chase(0, .05f, Chaser.exp(.1));
			tagFades.add(chase);

		});

		if (chapter != null) {
			widgets.add(chap = new PonderButton(width - 31 - 24, 31, () -> {
			}).showing(chapter));
		}

		GameSettings bindings = minecraft.gameSettings;
		int spacing = 8;
		int bX = (width - 20) / 2 - (70 + 2 * spacing);
		int bY = height - 20 - 31;

		widgets.add(scan = new PonderButton(bX, bY, () -> {
			identifyMode = !identifyMode;
			if (!identifyMode)
				scenes.get(index)
					.deselect();
		}).showing(AllIcons.I_MTD_SCAN)
			.shortcut(bindings.keyBindDrop)
			.fade(0, -1));

		bX += 50 + spacing;
		widgets.add(left = new PonderButton(bX, bY, () -> this.scroll(false)).showing(AllIcons.I_MTD_LEFT)
			.shortcut(bindings.keyBindLeft)
			.fade(0, -1));

		bX += 20 + spacing;
		widgets.add(new PonderButton(bX, bY, this::onClose).showing(AllIcons.I_MTD_CLOSE)
			.shortcut(bindings.keyBindInventory)
			.fade(0, -1));

		bX += 20 + spacing;
		widgets.add(right = new PonderButton(bX, bY, () -> this.scroll(true)).showing(AllIcons.I_MTD_RIGHT)
			.shortcut(bindings.keyBindRight)
			.fade(0, -1));

		bX += 50 + spacing;
		widgets.add(new PonderButton(bX, bY, this::replay).showing(AllIcons.I_MTD_REPLAY)
			.shortcut(bindings.keyBindBack)
			.fade(0, -1));

	}

	@Override
	public void tick() {
		super.tick();
		PonderScene activeScene = scenes.get(index);
		if (!identifyMode)
			activeScene.tick();
		sceneProgress.chase(activeScene.getSceneProgress(), .5f, Chaser.EXP);
		lazyIndex.tickChaser();
		fadeIn.tickChaser();
		sceneProgress.tickChaser();

		if (!identifyMode) {
			float lazyIndexValue = lazyIndex.getValue();
			if (Math.abs(lazyIndexValue - index) > 1 / 512f)
				scenes.get(lazyIndexValue < index ? index - 1 : index + 1)
					.tick();
		}

		updateIdentifiedItem(activeScene);
	}

	public void updateIdentifiedItem(PonderScene activeScene) {
		hoveredTooltipItem = ItemStack.EMPTY;
		hoveredBlockPos = null;
		if (!identifyMode)
			return;

		MainWindow w = minecraft.getWindow();
		double mouseX = minecraft.mouseHelper.getMouseX() * w.getScaledWidth() / w.getWidth();
		double mouseY = minecraft.mouseHelper.getMouseY() * w.getScaledHeight() / w.getHeight();
		SceneTransform t = activeScene.getTransform();
		Vec3d vec1 = t.screenToScene(mouseX, mouseY, 1000);
		Vec3d vec2 = t.screenToScene(mouseX, mouseY, -100);
		Pair<ItemStack, BlockPos> pair = activeScene.rayTraceScene(vec1, vec2);
		hoveredTooltipItem = pair.getFirst();
		hoveredBlockPos = pair.getSecond();
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
		if (scroll(delta > 0))
			return true;
		return super.mouseScrolled(mouseX, mouseY, delta);
	}

	protected void replay() {
		identifyMode = false;
		PonderScene scene = scenes.get(index);

		if (hasShiftDown()) {
			List<PonderStoryBoardEntry> list = PonderRegistry.all.get(scene.component);
			PonderStoryBoardEntry sb = list.get(index);
			Template activeTemplate = PonderRegistry.loadSchematic(sb.getSchematicName());
			PonderWorld world = new PonderWorld(BlockPos.ZERO, Minecraft.getInstance().world);
			activeTemplate.addBlocksToWorld(world, BlockPos.ZERO, new PlacementSettings());
			world.createBackup();
			scene = PonderRegistry.compileScene(index, sb, world);
			scene.begin();
			scenes.set(index, scene);
		}

		scene.begin();
	}

	protected boolean scroll(boolean forward) {
		int prevIndex = index;
		index = forward ? index + 1 : index - 1;
		index = MathHelper.clamp(index, 0, scenes.size() - 1);
		if (prevIndex != index) {// && Math.abs(index - lazyIndex.getValue()) < 1.5f) {
			scenes.get(prevIndex)
				.fadeOut();
			scenes.get(index)
				.begin();
			lazyIndex.chase(index, 1 / 4f, Chaser.EXP);
			identifyMode = false;
			return true;
		} else
			index = prevIndex;
		return false;
	}

	@Override
	protected void renderWindow(int mouseX, int mouseY, float partialTicks) {
		RenderSystem.enableBlend();
		renderVisibleScenes(mouseX, mouseY, identifyMode ? 0 : partialTicks);
		renderWidgets(mouseX, mouseY, identifyMode ? 0 : partialTicks);
	}

	protected void renderVisibleScenes(int mouseX, int mouseY, float partialTicks) {
		renderScene(mouseX, mouseY, index, partialTicks);
		float lazyIndexValue = lazyIndex.getValue(partialTicks);
		if (Math.abs(lazyIndexValue - index) > 1 / 512f)
			renderScene(mouseX, mouseY, lazyIndexValue < index ? index - 1 : index + 1, partialTicks);
	}

	protected void renderScene(int mouseX, int mouseY, int i, float partialTicks) {
		SuperRenderTypeBuffer buffer = SuperRenderTypeBuffer.getInstance();
		PonderScene story = scenes.get(i);
		MatrixStack ms = new MatrixStack();
		double value = lazyIndex.getValue(AnimationTickHolder.getPartialTicks());
		double diff = i - value;
		double slide = MathHelper.lerp(diff * diff, 200, 600) * diff;

		RenderSystem.enableAlphaTest();
		RenderSystem.enableBlend();
		RenderSystem.enableDepthTest();

		ms.push();
		story.transform.updateScreenParams(width, height, slide);
		story.transform.apply(ms, partialTicks);
		story.transform.updateSceneRVE();
		story.renderScene(buffer, ms, partialTicks);
		buffer.draw();

		// coords for debug
		if (PonderIndex.EDITOR_MODE) {
			MutableBoundingBox bounds = story.getBounds();

			RenderSystem.pushMatrix();
			RenderSystem.multMatrix(ms.peek()
				.getModel());

			RenderSystem.scaled(-1 / 16d, -1 / 16d, 1 / 16d);
			RenderSystem.translated(1, -8, -1 / 64f);

			// X AXIS
			RenderSystem.pushMatrix();
			RenderSystem.translated(4, -3, 0);
			for (int x = 0; x <= bounds.getXSize(); x++) {
				RenderSystem.translated(-16, 0, 0);
				font.drawString(x == bounds.getXSize() ? "x" : "" + x, 0, 0, 0xFFFFFFFF);
			}
			RenderSystem.popMatrix();

			// Z AXIS
			RenderSystem.pushMatrix();
			RenderSystem.scaled(-1, 1, 1);
			RenderSystem.translated(0, -3, -4);
			RenderSystem.rotatef(-90, 0, 1, 0);
			RenderSystem.translated(-8, -2, 2 / 64f);
			for (int z = 0; z <= bounds.getZSize(); z++) {
				RenderSystem.translated(16, 0, 0);
				font.drawString(z == bounds.getZSize() ? "z" : "" + z, 0, 0, 0xFFFFFFFF);
			}
			RenderSystem.popMatrix();

			// DIRECTIONS
			RenderSystem.pushMatrix();
			RenderSystem.translated(bounds.getXSize() * -8, 0, bounds.getZSize() * 8);
			RenderSystem.rotatef(-90, 0, 1, 0);
			for (Direction d : Iterate.horizontalDirections) {
				RenderSystem.rotatef(90, 0, 1, 0);
				RenderSystem.pushMatrix();
				RenderSystem.translated(0, 0, bounds.getZSize() * 16);
				RenderSystem.rotatef(-90, 1, 0, 0);
				font.drawString(d.name()
					.substring(0, 1), 0, 0, 0x66FFFFFF);
				font.drawString("|", 2, 10, 0x44FFFFFF);
				font.drawString(".", 2, 14, 0x22FFFFFF);
				RenderSystem.popMatrix();
			}
			RenderSystem.popMatrix();

			buffer.draw();
			RenderSystem.popMatrix();
		}

		ms.pop();
	}

	protected void renderWidgets(int mouseX, int mouseY, float partialTicks) {
		float fade = fadeIn.getValue(partialTicks);
		float lazyIndexValue = lazyIndex.getValue(partialTicks);
		float indexDiff = Math.abs(lazyIndexValue - index);
		PonderScene activeScene = scenes.get(index);
		int textColor = 0xeeeeee;

		{
			// Chapter title
			RenderSystem.pushMatrix();
			RenderSystem.translated(0, 0, 800);
			int x = 31 + 20 + 8;
			int y = 31;

			String title = activeScene.getTitle();
			int wordWrappedHeight = font.getWordWrappedHeight(title, left.x);

			int streakHeight = 35 - 9 + wordWrappedHeight;
			UIRenderHelper.streak(0, x - 4, y - 12 + streakHeight / 2, streakHeight, (int) (150 * fade), 0x101010);
			UIRenderHelper.streak(180, x - 4, y - 12 + streakHeight / 2, streakHeight, (int) (30 * fade), 0x101010);
			renderBox(21, 21, 30, 30, false);

			GuiGameElement.of(stack)
				.at(x - 39, y - 11)
				.scale(2)
				.render();

			drawString(font, Lang.translate(PONDERING), x, y - 6, 0xffa3a3a3);
			y += 8;
			x += 0;
			// RenderSystem.translated(0, 3 * (indexDiff), 0);
			RenderSystem.translated(x, y, 0);
			RenderSystem.rotatef(indexDiff * -75, 1, 0, 0);
			RenderSystem.translated(0, 0, 5);
			font.drawSplitString(title, 0, 0, left.x, ColorHelper.applyAlpha(textColor, 1 - indexDiff));
			RenderSystem.popMatrix();

			if (chapter != null) {
				RenderSystem.pushMatrix();

				RenderSystem.translated(chap.x - 4 - 4, chap.y, 0);
				UIRenderHelper.streak(180, 4, 10, 26, (int) (150 * fade), 0x101010);

				drawRightAlignedString(font, Lang.translate(IN_CHAPTER), 0, 0, 0xffa3a3a3);
				drawRightAlignedString(font,
					Lang.translate(PonderLocalization.LANG_PREFIX + "chapter." + chapter.getId()), 0, 12, 0xffeeeeee);

				RenderSystem.popMatrix();
			}
		}

		if (identifyMode) {
			RenderSystem.pushMatrix();
			RenderSystem.translated(mouseX, mouseY, 100);
			if (hoveredTooltipItem.isEmpty()) {
				String tooltip = Lang
					.createTranslationTextComponent(IDENTIFY_MODE,
						new StringTextComponent(minecraft.gameSettings.keyBindDrop.getKeyBinding()
							.getLocalizedName()).applyTextStyle(TextFormatting.WHITE))
					.applyTextStyle(TextFormatting.GRAY)
					.getFormattedText();
				renderTooltip(font.listFormattedStringToWidth(tooltip, width / 3), 0, 0);
			} else
				renderTooltip(hoveredTooltipItem, 0, 0);
			if (hoveredBlockPos != null && PonderIndex.EDITOR_MODE) {
				RenderSystem.translated(0, -15, 0);
				boolean copied = copiedBlockPos != null && hoveredBlockPos.equals(copiedBlockPos);
				String coords = new StringTextComponent(
					hoveredBlockPos.getX() + ", " + hoveredBlockPos.getY() + ", " + hoveredBlockPos.getZ())
						.applyTextStyles(copied ? TextFormatting.GREEN : TextFormatting.GOLD)
						.getFormattedText();
				renderTooltip(coords, 0, 0);
			}
			RenderSystem.popMatrix();

			scan.flash();
		} else {
			scan.dim();
		}

		{
			// Scene overlay
			RenderSystem.pushMatrix();
			RenderSystem.translated(0, 0, 100);
			renderOverlay(index, partialTicks);
			if (indexDiff > 1 / 512f)
				renderOverlay(lazyIndexValue < index ? index - 1 : index + 1, partialTicks);
			RenderSystem.popMatrix();
		}

		// Widgets
		widgets.forEach(w -> {
			if (w instanceof PonderButton) {
				PonderButton mtdButton = (PonderButton) w;
				mtdButton.fade(fade);
			}
		});

		if (index == 0 || index == 1 && lazyIndexValue < index)
			left.fade(lazyIndexValue);
		if (index == scenes.size() - 1 || index == scenes.size() - 2 && lazyIndexValue > index)
			right.fade(scenes.size() - lazyIndexValue - 1);

		boolean finished = activeScene.isFinished();
		if (finished)
			right.flash();
		else
			right.dim();

		{
			int x = (width / 2) - 110;
			int y = right.y + right.getHeight() + 4;
			int w = width - 2 * x;
			renderBox(x, y, w, 1, false);
			RenderSystem.pushMatrix();
			RenderSystem.translated(x - 2, y - 2, 0);
			RenderSystem.scaled((w + 4) * sceneProgress.getValue(partialTicks), 1, 1);
			GuiUtils.drawGradientRect(200, 0, 3, 1, 4, 0x60ffeedd, 0x60ffeedd);
			RenderSystem.popMatrix();
		}

		// Tags
		List<PonderTag> sceneTags = activeScene.tags;
		boolean highlightAll = sceneTags.contains(PonderTag.Highlight.ALL);
		double s = Minecraft.getInstance()
			.getWindow()
			.getGuiScaleFactor();
		IntStream.range(0, tagButtons.size())
			.forEach(i -> {
				RenderSystem.pushMatrix();
				LerpedFloat chase = tagFades.get(i);
				PonderButton button = tagButtons.get(i);
				if (button.isMouseOver(mouseX, mouseY)) {
					chase.updateChaseTarget(1);
				} else
					chase.updateChaseTarget(0);

				chase.tickChaser();

				if (highlightAll || sceneTags.contains(this.tags.get(i)))
					button.flash();
				else
					button.dim();

				int x = button.x + button.getWidth() + 4;
				int y = button.y - 2;
				RenderSystem.translated(x, y + 5 * (1 - fade), 800);

				float fadedWidth = 200 * chase.getValue(partialTicks);
				UIRenderHelper.streak(0, 0, 12, 26, (int) fadedWidth, 0x101010);

				GL11.glScissor((int) (x * s), 0, (int) (fadedWidth * s), (int) (height * s));
				GL11.glEnable(GL11.GL_SCISSOR_TEST);

				String tagName = this.tags.get(i)
					.getTitle();
				drawString(tagName, 3, 8, 0xffeedd);

				GL11.glDisable(GL11.GL_SCISSOR_TEST);

				RenderSystem.popMatrix();
			});
	}

	protected void lowerButtonGroup(int index, int mouseX, int mouseY, float fade, AllIcons icon, KeyBinding key) {
		int bWidth = 20;
		int bHeight = 20;
		int bX = (width - bWidth) / 2 + (index - 1) * (bWidth + 8);
		int bY = height - bHeight - 31;

		RenderSystem.pushMatrix();
		if (fade < fadeIn.getChaseTarget())
			RenderSystem.translated(0, (1 - fade) * 5, 0);
		boolean hovered = isMouseOver(mouseX, mouseY, bX, bY, bWidth, bHeight);
		renderBox(bX, bY, bWidth, bHeight, hovered);
		icon.draw(bX + 2, bY + 2);
		drawCenteredString(font, key.getLocalizedName(), bX + bWidth / 2 + 8, bY + bHeight - 6, 0xff606060);
		RenderSystem.popMatrix();
	}

	private void renderOverlay(int i, float partialTicks) {
		if (identifyMode)
			return;
		RenderSystem.pushMatrix();
		PonderScene story = scenes.get(i);
		MatrixStack ms = new MatrixStack();
		story.renderOverlay(this, ms, partialTicks);
		RenderSystem.popMatrix();
	}

	@Override
	public boolean mouseClicked(double x, double y, int button) {
		MutableBoolean handled = new MutableBoolean(false);
		widgets.forEach(w -> {
			if (handled.booleanValue())
				return;
			if (!w.isMouseOver(x, y))
				return;
			if (w instanceof PonderButton) {
				PonderButton mtdButton = (PonderButton) w;
				mtdButton.runCallback(x, y);
				handled.setTrue();
				return;
			}
		});

		if (handled.booleanValue())
			return true;

		if (identifyMode && hoveredBlockPos != null && PonderIndex.EDITOR_MODE) {
			clipboardHelper.setClipboardString(minecraft.getWindow()
				.getHandle(),
				"BlockPos copied = util.grid.at(" + hoveredBlockPos.getX() + ", " + hoveredBlockPos.getY() + ", "
					+ hoveredBlockPos.getZ() + ");");
			copiedBlockPos = hoveredBlockPos;
			return true;
		}

		return super.mouseClicked(x, y, button);
	}

	@Override
	public boolean keyPressed(int code, int p_keyPressed_2_, int p_keyPressed_3_) {
		GameSettings settings = Minecraft.getInstance().gameSettings;
		int sCode = settings.keyBindBack.getKey()
			.getKeyCode();
		int aCode = settings.keyBindLeft.getKey()
			.getKeyCode();
		int dCode = settings.keyBindRight.getKey()
			.getKeyCode();
		int qCode = settings.keyBindDrop.getKey()
			.getKeyCode();

		if (code == sCode) {
			replay();
			return true;
		}

		if (code == aCode) {
			scroll(false);
			return true;
		}

		if (code == dCode) {
			scroll(true);
			return true;
		}

		if (code == qCode) {
			identifyMode = !identifyMode;
			if (!identifyMode)
				scenes.get(index)
					.deselect();
			return true;
		}

		return super.keyPressed(code, p_keyPressed_2_, p_keyPressed_3_);
	}

	@Override
	protected String getBreadcrumbTitle() {
		if (chapter != null)
			return Lang.translate(PonderLocalization.LANG_PREFIX + "chapter." + chapter.getId());

		return stack.getItem()
			.getName()
			.getFormattedText();
	}

	public FontRenderer getFontRenderer() {
		return font;
	}

	protected boolean isMouseOver(double mouseX, double mouseY, int x, int y, int w, int h) {
		boolean hovered = !(mouseX < x || mouseX > x + w);
		hovered &= !(mouseY < y || mouseY > y + h);
		return hovered;
	}

	public void drawString(String s, int x, int y, int color) {
		drawString(font, s, x, y, color);
	}

	public static void renderBox(int x, int y, int w, int h, boolean highlighted) {
		renderBox(x, y, w, h, 0xff000000, highlighted ? 0xf0ffeedd : 0x40ffeedd, highlighted ? 0x60ffeedd : 0x20ffeedd);
	}

	public static void renderSpeechBox(int x, int y, int w, int h, boolean highlighted, Pointing pointing,
		boolean returnWithLocalTransform) {
		if (!returnWithLocalTransform)
			RenderSystem.pushMatrix();

		int boxX = x;
		int boxY = y;
		int divotX = x;
		int divotY = y;
		int divotRotation = 0;
		int divotSize = 8;
		int distance = 1;
		int divotRadius = divotSize / 2;

		switch (pointing) {
		default:
		case DOWN:
			divotRotation = 0;
			boxX -= w / 2;
			boxY -= h + divotSize + 1 + distance;
			divotX -= divotRadius;
			divotY -= divotSize + distance;
			break;
		case LEFT:
			divotRotation = 90;
			boxX += divotSize + 1 + distance;
			boxY -= h / 2;
			divotX += distance;
			divotY -= divotRadius;
			break;
		case RIGHT:
			divotRotation = 270;
			boxX -= w + divotSize + 1 + distance;
			boxY -= h / 2;
			divotX -= divotSize + distance;
			divotY -= divotRadius;
			break;
		case UP:
			divotRotation = 180;
			boxX -= w / 2;
			boxY += divotSize + 1 + distance;
			divotX -= divotRadius;
			divotY += distance;
			break;
		}

		renderBox(boxX, boxY, w, h, highlighted);

		RenderSystem.pushMatrix();
		AllGuiTextures toRender = highlighted ? AllGuiTextures.SPEECH_TOOLTIP_HIGHLIGHT : AllGuiTextures.SPEECH_TOOLTIP;
		RenderSystem.translated(divotX + divotRadius, divotY + divotRadius, 10);
		RenderSystem.rotatef(divotRotation, 0, 0, 1);
		RenderSystem.translated(-divotRadius, -divotRadius, 0);
		toRender.draw(0, 0);
		RenderSystem.popMatrix();

		if (returnWithLocalTransform) {
			RenderSystem.translated(boxX, boxY, 0);
			return;
		}

		RenderSystem.popMatrix();

	}

	public static void renderBox(int x, int y, int w, int h, int backgroundColor, int borderColorStart,
		int borderColorEnd) {
		int z = 100;
		GuiUtils.drawGradientRect(z, x - 3, y - 4, x + w + 3, y - 3, backgroundColor, backgroundColor);
		GuiUtils.drawGradientRect(z, x - 3, y + h + 3, x + w + 3, y + h + 4, backgroundColor, backgroundColor);
		GuiUtils.drawGradientRect(z, x - 3, y - 3, x + w + 3, y + h + 3, backgroundColor, backgroundColor);
		GuiUtils.drawGradientRect(z, x - 4, y - 3, x - 3, y + h + 3, backgroundColor, backgroundColor);
		GuiUtils.drawGradientRect(z, x + w + 3, y - 3, x + w + 4, y + h + 3, backgroundColor, backgroundColor);
		GuiUtils.drawGradientRect(z, x - 3, y - 3 + 1, x - 3 + 1, y + h + 3 - 1, borderColorStart, borderColorEnd);
		GuiUtils.drawGradientRect(z, x + w + 2, y - 3 + 1, x + w + 3, y + h + 3 - 1, borderColorStart, borderColorEnd);
		GuiUtils.drawGradientRect(z, x - 3, y - 3, x + w + 3, y - 3 + 1, borderColorStart, borderColorStart);
		GuiUtils.drawGradientRect(z, x - 3, y + h + 2, x + w + 3, y + h + 3, borderColorEnd, borderColorEnd);
	}

	public ItemStack getHoveredTooltipItem() {
		return hoveredTooltipItem;
	}

	public ItemStack getSubject() {
		return stack;
	}

	@Override
	public boolean isEquivalentTo(AbstractSimiScreen other) {
		if (other instanceof PonderUI)
			return stack.isItemEqual(((PonderUI) other).stack);
		return super.isEquivalentTo(other);
	}

}
