package me.lonaldeu.antifreecam;

/**
 * What a player may not see: everything at or below {@code maxHiddenY} (below {@code minY} for nothing), plus,
 * with {@code occlude}, the sections cave occlusion hides. {@code surface} means the player is above the
 * protection level; otherwise the cut follows the player's own depth.
 */
record View(int minY, int maxHiddenY, boolean surface, boolean occlude) {

    /**
     * The highest hidden Y for a player who sees down to {@code reach}: everything in the whole sections below it.
     * Moving up, the next section is only hidden once reach is {@code hysteresis} blocks past its top, so walking
     * or jumping across a section boundary doesn't flip the view; {@code held} is the current cut.
     */
    static int depthCut(double reach, int held, double hysteresis) {
        int cut = Math.floorDiv((int) Math.floor(reach), 16) * 16 - 1;
        return cut > held && reach < held + 17 + hysteresis ? held : cut;
    }
}
