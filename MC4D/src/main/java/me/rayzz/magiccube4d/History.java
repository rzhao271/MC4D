package me.rayzz.magiccube4d;

import java.io.*;
import java.util.*;



/**
 * Maintains a sequence of twists, rotates, and marks applied to a MagicCube4D puzzle.
 * Supports undo/redo and macro moves and is able to save and restore from log files.
 *
 * <p>DESIGN</p>
 * <li>Twists and rotates are called "moves". Rotates are represented internally as
 * twists that affect all slices but are logically considered a different kind of move.</li>
 * <li>Marks are single character delimiters that can be inserted between like bookmarks.</li>
 * <li>Moves and marks are called history nodes. </li>
 * <li>Macros are represented internally by a sequence of nodes bracketed by the reserved
 * characters '[' and ']'.</li>
 * <li>There is a reference to a "current" move which may be any node or null and can be
 * accessed via getCurrent() and controlled with the various goToXxxx() methods.</li>
 * <li>Notification of changes to the current node can be listened to.</li>
 *
 * Copyright 2005 - Superliminal Software
 * @author Don Hatch
 * @author Melinda Green
 */
public class History {

    static private void Assert(boolean condition) { if (!condition) throw new Error("Assertion failed"); }

    public final static char
        MARK_MACRO_OPEN        = '[',
        MARK_MACRO_CLOSE       = ']',
        MARK_SCRAMBLE_BOUNDARY = '|';

    private int length;
    private static class HistoryNode {
        public int stickerid;
        public int dir;
        public int slicesmask;
        public char mark;
        public HistoryNode prev, next;    /* doubly linked list */
		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			HistoryNode other = (HistoryNode) obj;
			if (dir != other.dir)
				return false;
			if (mark != other.mark)
				return false;
			if (slicesmask != other.slicesmask)
				return false;
			if (stickerid != other.stickerid)
				return false;
			return true;
		}
		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + dir;
			result = prime * result + mark;
			result = prime * result + slicesmask;
			result = prime * result + stickerid;
			return result;
		}
    }
    private HistoryNode first, last, current;

    public History(int length) {
    	this.length = length;
        //debug = preferences.getBoolProperty(M4D_HISTORY_DEBUG);
    }

    public Enumeration<MagicCube.TwistData> moves() {
        return new Enumeration<MagicCube.TwistData>() {
        	private Queue<HistoryNode> queue = findTwists();
			@Override
			public boolean hasMoreElements() {
				return !queue.isEmpty();
			}
			@Override
			public MagicCube.TwistData nextElement() {
				HistoryNode n = queue.remove();
				return new MagicCube.TwistData(n.stickerid, n.dir, n.slicesmask);
			}
			private Queue<HistoryNode> findTwists() {
				Queue<HistoryNode> twists = new LinkedList<HistoryNode>();
				for(HistoryNode n = first; n!=null && n!=current; n=n.next)
	        		if(n.mark == 0)
	        			twists.add(n);
				return twists;
			}
        };
    }

    private void deleteNode(HistoryNode node) {
        if (node == null)
            return;
        boolean changed = false;
        if (current == node) {
            current = node.next;
            changed = true;
        }
        if (node.prev == null)
            first = node.next;
        else
            node.prev.next = node.next;
        if (node.next == null)
            last = node.prev;
        else
            node.next.prev = node.prev;
        if(changed)
            fireCurrentChanged();
    }

    private void insertNode(HistoryNode node_to_insert_before, int stickerid, int dir, int slicesmask) {
        insertNode(node_to_insert_before, stickerid, dir, slicesmask, (char)0);
    }

    private void insertNode(HistoryNode node_to_insert_before, int stickerid, int dir, int slicesmask, char mark) {
        HistoryNode temp = new HistoryNode();
        temp.stickerid = stickerid;
        temp.dir = dir;
        temp.slicesmask = slicesmask;
        temp.mark = mark;
        temp.prev = (node_to_insert_before==null ? last : node_to_insert_before.prev);
        temp.next = node_to_insert_before;
        if (temp.next == null)
            last = temp;
        else
            temp.next.prev = temp;
        if (temp.prev == null)
            first = temp;
        else
            temp.prev.next = temp;
    }

    public void deleteLast() {
        deleteNode(last);
    }

    public void clear(int newLength) {
    	length = newLength;
        while (first != null)
            deleteLast();
    }

    public void clear() {
    	clear(length);
    }

    public void append(int stickerid, int dir, int slicesmask) {
        if(slicesmask == 0)
            slicesmask = 1; // 0 means slicemask 1 so keep them consistent so they always compare equal.
        HistoryNode node = getPreviousMove();
        if(!atMacroClose() // to not corrupt any macro. can screw up edit->cheat (issue 66) but compressing history first may fix that.
            && node != null // there is a previous twist
            && node.stickerid == stickerid // on the same axis
            && node.slicesmask == slicesmask // affecting the same slices
            && node.dir == -dir) // but in *opposite* direction
        {
            undo(); // just back the move out rather than append an inverse move
            truncate(); // had to add this so write() doesn't save moves after new current
        }
        else
            insertNode(current, stickerid, dir, slicesmask);
        fireCurrentChanged();
    }

    /**
     * Simply calls 3-arg version with data from given move.
     */
    public void append(MagicCube.TwistData move) {
        append(move.grip.id_within_puzzle, move.direction, move.slicemask);
    }

    /*
     * If there is a "current", delete it and everything after it.
     */
    public void truncate() {
        while (current != null)
            deleteLast();
        // Special case: If we are at a macro open mark, eat it.

        // FIX THIS -- see comments in
        // EventHandler.applyMacroCBAfterGotRefStickers about how this
        // makes things awkward at time of application of macro.  The
        // marking of macros is a little problematic implemented this way,
        // but the current kludges seem to work right.  One needs to be
        // able to undo/redo past macros in fast automove mode and to have
        // no stray m[ in the file after doing a move following undoing a
        // macro or after applying a macro after undoing a macro.
        if (atMacroOpen())
        {
            //deleteLast();
        }
    }


    /*
     * Put a single move into the history.
     * This clears the history after the current point,
     * so a "redo" is impossible afterwards.
     */
    public void apply(MagicCube.Stickerspec sticker, int dir, int slicesmask) {
        truncate();
        append(sticker.id_within_puzzle, dir, slicesmask);
    }

    /**
     * Simply calls 3-arg version with data from given move.
     */
    public void apply(MagicCube.TwistData move) {
        apply(move.grip, move.direction, move.slicemask);
    }

    private boolean isRotate(int slicemask) {
        for(int i=0; i<length; i++)
            if((slicemask & 1<<i) ==0)
                return false;
        return true;
    }


    public int countTwists() {
        return countMoves(true);
    }

    public int countMoves(boolean excludeRotates) {
        int result=0;
        boolean hitscrambleboundary = false;
        for (HistoryNode cur_node = first; cur_node!=null && cur_node!=current; cur_node = cur_node.next)
            if (cur_node.stickerid >= 0) {
                if( ! (excludeRotates && isRotate(cur_node.slicesmask)))
                    ++result;
            }
            else
                if( ! hitscrambleboundary) {
                    if(cur_node.mark == MARK_SCRAMBLE_BOUNDARY) {
                        hitscrambleboundary = true;
                        result = 0;
                    }
                }
        return result;
    }

    private MagicCube.TwistData getCurrent() {
        return new MagicCube.TwistData(current.stickerid, current.dir, current.slicesmask);
    }


    /* Set current to be the beginning of the list. */
    public void goToBeginning() { current = first; fireCurrentChanged(); }
    public void goToEnd() { current = null; fireCurrentChanged(); }
    public boolean goToPrevious() { if(current==null) return false; current = current.prev; fireCurrentChanged(); return true; }
    public boolean goToNext()     { if(current==null) return false; current = current.next; fireCurrentChanged(); return true; }

//    public MagicCube.TwistData goTo(int twistnum) {
//        int i=0;
//        current=first;
//        while(current!=null) {
//            if(i++ == twistnum)
//                return getCurrent();
//            while(current.stickerid==-1)
//                current = current.next;
//            current = current.next;
//        }
//        return null;
//    }


    /**
     * Back up one move in the history, returning a move
     * that would undo the last move or null if nothing to undo.
     */
    public MagicCube.TwistData undo()
    {
        //search backwards to the next actual move
        HistoryNode node;
        for (node = getPrevious(); node != null; node = node.prev) // not quite the same as getPreviousTwist()?
            if (node.stickerid != -1)
                break;
        if (node == null)
            return null;
        current = node;
        MagicCube.TwistData toundo = getCurrent();
        toundo.direction *= -1;
        fireCurrentChanged();
        return toundo;
    }

    /**
     * Go forward one move in the history, returning the move
     * to redo or null if there is nothing to redo.
     * This is only valid if a move was undone.
     */
    public MagicCube.TwistData redo()
    {
        if(current == null)
            return null;
        while(current!=null && current.stickerid == -1)
            current = current.next;
        if(current == null)
            return null;
        MagicCube.TwistData toredo = getCurrent();
        current = current.next;
        fireCurrentChanged();
        return toredo;
    }

    /**
     * @return true if history has a previous actual twist or rotate.
     */
    public boolean hasPreviousMove() {
        for (HistoryNode node=current==null?last:current; node!=null; node=node.prev)
            if (node.stickerid != -1)
                return true;
        return false;
    }

    /**
     * @return the last actual twist or rotate.
     */
    public HistoryNode getPreviousMove() {
        for (HistoryNode node = current==null?last:current; node != null; node = node.prev)
            if (node.stickerid != -1)
                return node;
        return null;
    }

    public boolean hasNextMove() {
        for (HistoryNode node = current; node != null; node = node.next)
            if (node.stickerid != -1)
                return true;
        return false;
    }


    /**
     * @return most recent history node whether actual move or not.
     */
    private HistoryNode getPrevious() {
        return (current!=null ? current.prev : last);
    }

    /**
     * @return next history node whether actual move or not.
     *
    private HistoryNode getNext() {
        return (current!=null ? current.next : null);
    }*/

    //
    // MARK METHODS
    //

    private MagicCube.TwistData goBackwardsTowardsMark(int mark) {
        // Continue searching backwards for a potential undo
        for (HistoryNode node = getPrevious(); node!=null; node = node.prev)
            if (node.stickerid == -1 && node.mark == mark)
                return undo();
        return null;
    }

    private MagicCube.TwistData goForwardsTowardsMark(int mark) {
        // Search forwards for a potential redo
        for (HistoryNode node=current; node!=null; node = node.next)
            if (node.stickerid == -1 && node.mark == mark)
                return redo();
        return null;
    }

    /**
     * Executes an undo or redo.
     * If forward_first is true and we are not at the mark, then search
     * forward first and search backward only if the mark is not ahead of us.
     * Otherwise, search backward first and search forward only if
     * the mark is not behind us.  Being able to choose which way to go
     * first makes it useful to have multiple marks with the same identifier.
     *
     * @return resulting twist if successful, null if already at the mark or no such mark.
     */
    public MagicCube.TwistData goTowardsMark(int mark, boolean forward_first) {
        if (atMark(mark))
            return null; // already at the mark
        MagicCube.TwistData status;
        if (! forward_first) {
            status = this.goBackwardsTowardsMark(mark);
            if (status != null)
                return status;
        }
        status = this.goForwardsTowardsMark(mark);
        if (status != null)
            return status;
        if (forward_first)
            status = this.goBackwardsTowardsMark(mark);
        return status;
    }

    public void mark(char mark) {
        insertNode(current, -1, 0, 0, mark);
    }

    public boolean atMark(int mark) {
        if(current!=null && current.stickerid==-1&&current.mark==mark)
            return true;
        /*
         * Go through all marks at the current position
         */
        for (HistoryNode node = (current!=null ? current.prev : last);
             node!=null && node.stickerid == -1;
             node = node.prev)
        {
            if (node.stickerid == -1 && node.mark == mark)
            {
                return true;
            }
        }
        return false;
    }

    public boolean atMacroOpen()        { return atMark(MARK_MACRO_OPEN); }
    public boolean atMacroClose()       { return atMark(MARK_MACRO_CLOSE); }
    public boolean atScrambleBoundary() { return atMark(MARK_SCRAMBLE_BOUNDARY); }


    //
    // I/O METHODS
    //

    public void write(Writer writer) {

        Assert(isSane());
        try {
            int nwritten = 0;
            for (HistoryNode node = first; node!=null && node!=current; node = node.next) {
                // Note: I added the "!=current" above to truncate any history after current
                // when saving, therefore the following should never happen but I'll leave
                // it in case we later want to bring it back. - MG
                if (node == current)
                    writer.write("c ");
                if (node.stickerid >= 0) {
                	writer.write("" + node.stickerid);
                    writer.write("," + node.dir);
                    writer.write("," + node.slicesmask);
                }
                else
                    writer.write("m" + node.mark);
                nwritten++;
                if (node.next != null && node.next!=current) {
                	if(nwritten % 10 == 0) // write a line break
                		writer.write(System.getProperty("line.separator"));
                	else
                		writer.write(" ");
                }
            }
            writer.write("." + System.getProperty("line.separator")); // end of history marker
        } catch(IOException ioe) {
            ioe.printStackTrace();
        }
    } // end write

    public boolean read(PushbackReader pr) {
        HistoryNode who_will_point_to_current = null;
        clear();
        try {
            while (true) {
                int c;
                while ((c = pr.read()) != -1 && Character.isWhitespace(c))
                    ;
                if (c == -1)
                    return outahere(); // premature end of file
                if (c == '.')
                    break; // end of history
                if (Character.isDigit(c)) { // read a node
                	pr.unread(c);
                    int sticker = readInt(pr);
                    if(pr.read() != ',')
                    	return outahere();
                    int direction = readInt(pr);
                    if(pr.read() != ',')
                    	return outahere();
                    int slicesmask = readInt(pr);
                    append(sticker, direction, slicesmask);
                    //System.out.println("read " + sticker + "," + direction + "," + slicesmask);
                } else if (c == 'm') {
                    c = pr.read();
                    mark((char)c);
                } else if (c == 'c') {
                    who_will_point_to_current = last == null ? first : last.next;
                } else {
                    System.out.println("bad hist char " + c);
                    return outahere();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return outahere();
        }
        current = who_will_point_to_current == null ? (last==null?null:last.next) : who_will_point_to_current.next;
        fireCurrentChanged();
        return true;
    } // end read

    /**
     * Reads a simple decimal integer.
     * @param pr PushbackReader to read from.
     * @return integer value of parsed number.
     * @throws NumberFormatException
     */
    public static int readInt(PushbackReader pr) throws NumberFormatException {
        char buf[] = new char[10];
        int c, chars=0;
        try {
            // check the first char for negative sign
            do { c = pr.read(); } while(Character.isWhitespace(c)); // skip whitespace
            if(c == '-')
                buf[chars++] = '-';
            else
                pr.unread(c);
            // read the digits
            while((c = pr.read()) != -1 && Character.isDigit(c)) // read digits
                buf[chars++] = (char)c;
            pr.unread(c);
        } catch (Exception ioe) {
            throw new NumberFormatException("Read error in History.readInt");
        }
        // convert the string to an integer
        String numstr = new String(buf, 0, chars);
        return Integer.parseInt(numstr);
    }

    private boolean outahere() {
        //System.err.println("Error reading history-- no history read");
        //Thread.dumpStack();
        clear();
        return false;
    }

    private boolean isSane() {
        boolean found_current = false;
        //int ngrips = MagicCube.NGRIPS;

        Assert((first == null) == (last == null));

        if (first != null) {
            for (HistoryNode node = first; node != null; node = node.next) {
                if (node.prev != null)
                    Assert(node.prev.next == node);
                else
                    Assert(first == node);
                if (node.next != null)
                    Assert(node.next.prev == node);
                else
                    Assert(last == node);

                if (node == current) {
                    Assert(node.stickerid >= 0);
                    found_current = true;
                }

                if (node.stickerid >= 0) {
                    //Assert(0 <= node.stickerid && node.stickerid < ngrips); // TODO: fix this
                    Assert(node.dir == MagicCube.CCW || node.dir == MagicCube.CW);
                }
            }
        }

        Assert(found_current == (current != null));

        return true;
    } // end isSane


    //
    // LISTENER SUPPORT
    //
    public static interface HistoryListener { public void currentChanged(); }
    private Set<HistoryListener> historyListeners = new HashSet<HistoryListener>();
    public void addHistoryListener(HistoryListener listener) { historyListeners.add(listener); }
    public void removeHistoryListener(HistoryListener listener) { if(historyListeners.contains(listener)) historyListeners.remove(listener); }
    protected void fireCurrentChanged() { for(HistoryListener hl : historyListeners) hl.currentChanged(); }


    /**
     * Converts a list of twists into an equivalent and possibly shorter list.
     * @param inmoves input array of moves to compress.
     * @param len edge length of the puzzle. Note: <i>not</i> the length of the moves array.
     * @return possibly reduced list of moves that produce the same effect as the input moves.
     */
    public static MagicCube.TwistData[] compress(MagicCube.TwistData[] inmoves, int len, boolean sweepRotatesForward) {
        History hist = new History(len);
        for(int i=0; i<inmoves.length; i++)
            hist.append(inmoves[i]);
        hist.compress(sweepRotatesForward);
        MagicCube.TwistData[] compressed = new MagicCube.TwistData[hist.countMoves(false)];
        int i=0;
        for(java.util.Enumeration<MagicCube.TwistData> outmoves=hist.moves(); outmoves.hasMoreElements(); )
            compressed[i++] = outmoves.nextElement();
        if(i != compressed.length)
            System.err.println("compress(TwistData[]) failed");
        return compressed;
    }

    /*
     * Reverses both the order of the history moves and their directions.
     *
     * Note: Also kills current, if any, just due to laziness.
     */
    private void reverse()
    {
    	if(first == null) return;
    	current = null; // so as to not fire change event
        HistoryNode origFirst = first;
        origFirst.dir *= -1; // the other nodes get reversed below but don't forget this one!
        int count = countMoves(false);
        for(int i=0; i<count-1; i++) {
        	HistoryNode lastLast = last;
        	deleteLast();
        	insertNode(origFirst, lastLast.stickerid, -lastLast.dir, lastLast.slicesmask, lastLast.mark);
        }
    }

    /**
     * Meant to squeeze out all redundancies and filler.
     * This is usually done in preparation for a "cheat" solve.
     *
     * <li>Truncate (i.e. delete everything past current),</li>
     * <li>Remove non-moves (marks),</li>
     * <li>Merge same-face twists,</li>
     * <li>Sweeping rotates to the beginning.</li>
     *
     * If sweepRotatesForward is set, does it in the opposite
     * direction.  In this case it's assumed to not be a real history,
     * and current is not allowed to be set.
     */
    public void compress(boolean sweepRotatesForward) {
    	// TODO: Uncomment the body below and fix.
    }
//
//    	//int startCount = this.countMoves(false);
//
//        if (sweepRotatesForward)
//        {
//            Assert(current == null);
//            reverse();
//            compress(false);
//            reverse();
//            return;
//        }
//
//        /*
//         * Truncate
//         */
//        truncate();
//
//        /*
//         * Remove all non-moves
//         */
//        for (HistoryNode node = first; node != null; node = node.next)
//            if (node.stickerid == -1)
//                deleteNode(node);
//
//        /*
//         * Traverse from end to beginning,
//         * constructing a new list of "mega-moves".
//         * Each mega-move is a set of twists on parallel slices.
//         * Sweep the current rotation towards the beginning as we go.
//         */
//        MagicCube.Stickerspec scratchGrip = new MagicCube.Stickerspec(); // scratch
//        int scratchMat[][] = new int[4][4];
//        int scratchCoords[] = new int[4];
//
//        class MegaMove {
//            public int face; // must be less than its opposite
//            public int sliceTwistMats[][][] = new int[length][4][4];
//            public MegaMove(int face, int length)
//            {
//                this.face = face;
//                this.sliceTwistMats = new int[length][4][4];
//                for (int i = 0; i < length; ++i)
//                    Vec_h._IDENTMAT4(sliceTwistMats[i]);
//            }
//            public String toString()
//            {
//                return "{face="+face+"...}";
//            }
//        };
//        int current_matrix[][] = new int[4][4];
//        Vec_h._IDENTMAT4(current_matrix);
//        LinkedList<MegaMove> megaMoves = new LinkedList<MegaMove>();
//        for (HistoryNode node = last; node != null; node = node.prev)
//        {
//            int stickerid = node.stickerid;
//            int slicesmask = node.slicesmask;
//            int dir = node.dir;
//
//            //
//            // Figure out the grip
//            //
//            MagicCube.Stickerspec grip = scratchGrip;
//            grip.id_within_puzzle = stickerid;
//            //PolygonManager.fillStickerspecFromIdAndLength(grip, 3); // XXX crashes with non cubes
//
//            //
//            // Transform the move by current_matrix,
//            // by applying current_matrix to the coords of the grip,
//            // then get the new stickerid back out of it.
//            //
//            {
//                Vec_h._VXM4i(grip.coords, grip.coords, current_matrix);
//                PolygonManager.fillStickerspecFromCoordsAndLength(grip, 3);
//                stickerid = grip.id_within_puzzle;
//            }
//
//            int face = PolygonManager.faceOfGrip(stickerid);
//            int oppositeFace = PolygonManager.oppositeFace(face);
//
//            //
//            // See if this move can be part of the first megamove.
//            // If not, insert a new megamove for it.
//            //
//            MegaMove firstMegaMove = (megaMoves.isEmpty() ? null
//                                             : megaMoves.getFirst());
//            if (firstMegaMove == null
//             || (face != firstMegaMove.face
//              && oppositeFace != firstMegaMove.face))
//            {
//                // Can't combine with the existing first megamove,
//                // so make a new one.
//                firstMegaMove = new MegaMove(Math.min(face, oppositeFace),
//                                             length);
//                megaMoves.addFirst(firstMegaMove);
//            }
//
//            //
//            // Twist this move's slices on the megamove
//            //
//            float angle = PolygonManager.getTwistTotalAngle(grip.dim, dir);
//            Math4d.get4dTwistMat(grip.coords, angle, scratchMat);
//            for (int iSlice = 0; iSlice < length; ++iSlice)
//            {
//                if (((slicesmask>>iSlice)&1) != 0)
//                {
//                    int iSliceCanonical = (oppositeFace<face ? length-1-iSlice
//                                                             : iSlice);
//                    Vec_h._MXM4i(firstMegaMove.sliceTwistMats[iSliceCanonical],
//                                 scratchMat,
//                                 firstMegaMove.sliceTwistMats[iSliceCanonical]);
//                }
//            }
//
//            //
//            // The slices now vote on what rotation to factor out
//            // of the megamove.
//            // If they all agree, then it's
//            // a pure rotation in which case the mega-move turns
//            // into a no-op, which can be removed.
//            //
//            if (true)
//            {
//                int winnerVotes = -1;
//                int winnerSlice = -1;
//                for (int iSlice = 0; iSlice < length; ++iSlice)
//                {
//                    int nSameAsISlice = 1;
//                    for (int jSlice = iSlice+1; jSlice < length; ++jSlice)
//                        if (Vec_h._EQMAT4(firstMegaMove.sliceTwistMats[jSlice],
//                                          firstMegaMove.sliceTwistMats[iSlice]))
//                            nSameAsISlice++;
//                    if (nSameAsISlice > winnerVotes)
//                    {
//                        winnerVotes = nSameAsISlice;
//                        winnerSlice = iSlice;
//                    }
//                }
//                Vec_h._MXM4i(current_matrix,
//                             current_matrix,
//                             firstMegaMove.sliceTwistMats[winnerSlice]);
//                Vec_h._TRANSPOSE4(scratchMat, firstMegaMove.sliceTwistMats[winnerSlice]); // inverse
//                for (int iSlice = 0; iSlice < length; ++iSlice)
//                    Vec_h._MXM4i(firstMegaMove.sliceTwistMats[iSlice],
//                                 scratchMat,
//                                 firstMegaMove.sliceTwistMats[iSlice]);
//                if (winnerVotes == length)
//                {
//                    megaMoves.removeFirst(); // it was a pure rotation
//                    firstMegaMove = null;
//                }
//            }
//        }
//
//        //
//        // The proper thing to do now would be to add the rotate(s)
//        // representing current_matrix
//        // to the beginning of the mega-moves, but I'm not bothering,
//        // since the only thing this function is used for anyway
//        // is the cheat-solve.
//        //
//
//        //
//        // Convert the mega-moves back into twists...
//        //
//        clear();
//        int scratchPermutation[] = new int[length];
//        while (!megaMoves.isEmpty())
//        {
//            // So we look at slices in a random order
//            randomPermutation(scratchPermutation);
//
//            MegaMove firstMegaMove = (MegaMove)megaMoves.removeLast();
//            int face = firstMegaMove.face;
//            for (int _iSlice = 0; _iSlice < length; ++_iSlice)
//            {
//                int iSlice = scratchPermutation[_iSlice];
//                int sliceTwistMat[][] = firstMegaMove.sliceTwistMats[iSlice];
//                if (!Vec_h._ISIDENTMAT4(sliceTwistMat))
//                {
//                    int slicesmask = 1<<iSlice;
//                    for (int jSlice = 0; jSlice < length; ++jSlice)
//                    {
//                        if (Vec_h._EQMAT4(firstMegaMove.sliceTwistMats[jSlice],
//                                          sliceTwistMat))
//                        {
//                            slicesmask |= 1<<jSlice;
//                            // Clear it so we don't do it again
//                            // as an iSlice later.  But do NOT clear [iSlice],
//                            // since sliceTwistMat is still pointing to it.
//                            if (jSlice != iSlice)
//                                Vec_h._IDENTMAT4(firstMegaMove.sliceTwistMats[jSlice]);
//                        }
//                    }
//
//                    /*
//                     * Figure out how to accomplish this rotation
//                     * of the slices with one or two twists on a single grip.
//                     */
//
//                    /*
//                     * Find a sticker on this face that's not
//                     * moved by the matrix; that will be the grip of the
//                     * concatenated move.
//                     */
//                    MagicCube.Stickerspec grip = scratchGrip;
//                    grip.face = face;
//                    for (grip.id_within_face = 0;
//                         grip.id_within_face < MagicCube.GRIPS_PER_FACE; // TODO: need way to know how many
//                         grip.id_within_face++)
//                    {
//                        PolygonManager.fillStickerspecFromFaceAndIdAndLength(grip, 3);
//                        if (grip.dim >= 3)
//                            continue;
//                        int newcoords[] = scratchCoords;
//                        Vec_h._VXM4(newcoords, grip.coords, sliceTwistMat);
//                        if (Vec_h._EQVEC4(newcoords, grip.coords)) {
//                            /*
//                             * Found the right grip;
//                             * see if any of the following work:
//                             *  0 twists
//                             *  1 twist CW
//                             *  1 twist CCW
//                             *  2 twists in random direction
//                             */
//                            int testmat[][] = scratchMat;
//                            Vec_h._IDENTMAT4(testmat);
//                            if (Vec_h._EQMAT4(testmat, sliceTwistMat)) {
//                                /*
//                                 * Result is 0 twists.
//                                 */
//                                break;
//                            }
//
//                            float angle = PolygonManager.getTwistTotalAngle(grip.dim, MagicCube.CCW);
//                            Math4d.get4dTwistMat(grip.coords, angle, testmat);
//                            if (Vec_h._EQMAT4(testmat, sliceTwistMat)) {
//                                /*
//                                 * Result is 1 twist CCW.
//                                 */
//                                insertNode(first, grip.id_within_puzzle, MagicCube.CCW, slicesmask);
//                                break;
//                            }
//                            angle = PolygonManager.getTwistTotalAngle(grip.dim, MagicCube.CW);
//                            Math4d.get4dTwistMat(grip.coords, angle, testmat);
//                            if (Vec_h._EQMAT4(testmat, sliceTwistMat)) {
//                                /*
//                                 * Result is 1 twist CW.
//                                 */
//                                insertNode(first, grip.id_within_puzzle, MagicCube.CW, slicesmask);
//                                break;
//                            }
//                            int dir = Math.random() > 0.5 ? MagicCube.CW : MagicCube.CCW;
//                            angle = PolygonManager.getTwistTotalAngle(grip.dim, MagicCube.CCW);
//                            Math4d.get4dTwistMat(grip.coords, angle, testmat);
//                            Vec_h._MXM4i(testmat, testmat, testmat);
//                            if (Vec_h._EQMAT4(testmat, sliceTwistMat)) {
//                                // Result is 2 twists
//                                insertNode(first, grip.id_within_puzzle, dir, slicesmask);
//                                insertNode(first, grip.id_within_puzzle, dir, slicesmask);
//                                break;
//                            }
//                            Assert(false);
//                        }
//                    }
//                    Assert(grip.id_within_face < 3 * 3 * 3);
//                }
//            }
//        }
//        //int endCount = this.countMoves(false);
//        //System.out.println("compressed " + startCount+ " twist sequence to " + endCount + " (" + (startCount - endCount)*100f/startCount + "%)");
//    } // end compress
//
//
//    private static void randomPermutation(int perm[])
//    {
//        for (int i = 0; i < perm.length; ++i)
//            perm[i] = i;
//        for (int i = perm.length-1; i >= 0; --i)
//        {
//            int j = (int)(Math.random()*(i+1)); // in [0..i]
//            if (j != i)
//            {
//                int temp = perm[i];
//                perm[i] = perm[j];
//                perm[j] = temp;
//            }
//        }
//    }
//
//
//    public void oldcompress() {
//        // TODO: perform on the fly, as the cheat-solve is happening, otherwise long wait if the history is long.
//        HistoryNode node, nodeptr;
//        int
//            current_matrix[][] = new int[4][4],
//            incmat[][] = new int[4][4],
//            testmat[][] = new int[4][4],
//            newcoords[] = new int[4];
//        MagicCube.Stickerspec grip = new MagicCube.Stickerspec();
//        int face, dir, thisface, nextface, temp;
//
//        /*
//         * Truncate
//         */
//        truncate();
//
//        /*
//         * Remove all non-moves
//         */
//        for (nodeptr = first; nodeptr!=null; nodeptr=nodeptr.next) {
//            if (nodeptr.stickerid == -1)
//                deleteNode(nodeptr);
//        }
//
//        /*
//         * Sweep all rotates to beginning
//         */
//        Vec_h._IDENTMAT4(current_matrix);
//        for (nodeptr=last; nodeptr!=null; nodeptr=nodeptr.prev) {
//            if (isRotate(nodeptr.slicesmask)) {
//                /*
//                * It's a rotate.  Just preconcatenate it
//                * to the current matrix and remove.
//                */
//                grip.id_within_puzzle = (nodeptr).stickerid;
//                PolygonManager.fillStickerspecFromIdAndLength(grip, 3);
//                float angle = PolygonManager.getTwistTotalAngle(grip.dim, (nodeptr).dir);
//                Math4d.get4dTwistMat(grip.coords, angle, incmat);
//                Vec_h._MXM4i(current_matrix, incmat, current_matrix);
//                deleteNode(nodeptr);
//            } else {
//                /*
//                * It's a twist (some slices stay and some move).
//                * Apply the current matrix to the coords of
//                * the grip.
//                */
//                grip.id_within_puzzle = (nodeptr).stickerid;
//                PolygonManager.fillStickerspecFromIdAndLength(grip, 3);
//                Vec_h._VXM4i(grip.coords, grip.coords, current_matrix);
//                PolygonManager.fillStickerspecFromCoordsAndLength(grip, 3);
//                (nodeptr).stickerid = grip.id_within_puzzle;
//            }
//        }
//        /*
//         * The proper thing to do now would be to add the rotates
//         * to the beginning of the history, but I'm not bothering,
//         * since the only thing this function is used for anyway
//         * is the cheat-solve.
//         */
//
//        /*
//         * Put opposite-face twists in canonical order,
//         * which can put some same-face twists together for the next pass.
//         */
//        for (node = first; node!=null; ) {
//            if (node.slicesmask == 1 && node.next!=null && node.next.slicesmask == 1) {
//                thisface = PolygonManager.faceOfGrip(node.stickerid);
//                nextface = PolygonManager.faceOfGrip(node.next.stickerid);
//                if (nextface < thisface && nextface == PolygonManager.oppositeFace(thisface)) {
//                    temp=node.stickerid; node.stickerid=node.next.stickerid; node.next.stickerid=temp;     // swap
//                    temp=node.dir; node.dir=node.next.dir; node.next.dir=temp;                             // swap
//                    temp=node.slicesmask; node.slicesmask=node.next.slicesmask; node.next.slicesmask=temp; // swap
//                    // XXX wtf is the following doing?? doesn't hurt, but I don't understand what it does -Don
//                    if (node.prev != null)
//                        node = node.prev;
//                }
//                else
//                    node = node.next;
//            }
//            else
//                node = node.next;
//        }
//
//        /*
//         * Merge same-face twists
//         */
//        HistoryNode first_on_this_face, past_last_on_this_face;
//        for (first_on_this_face = first; first_on_this_face!=null; ) {
//            if (first_on_this_face.slicesmask == 1) {
//                face = PolygonManager.faceOfGrip(first_on_this_face.stickerid);
//                past_last_on_this_face = first_on_this_face.next;
//                while (past_last_on_this_face!=null &&
//                       past_last_on_this_face.slicesmask == 1 &&
//                       PolygonManager.faceOfGrip(past_last_on_this_face.stickerid) == face)
//                {
//                    past_last_on_this_face = past_last_on_this_face.next;
//                }
//
//                if (past_last_on_this_face != first_on_this_face.next) {
//                    /*
//                     * There is more than one twist on this face.
//                     * Concatenate together all the matrices of these
//                     * twists, and then figure out how to accomplish it
//                     * with one or two twists on a single grip.
//                     */
//                    Vec_h._IDENTMAT4(current_matrix);
//                    for (node=first_on_this_face; node!=past_last_on_this_face; node=node.next) {
//                        grip.id_within_puzzle = node.stickerid;
//                        PolygonManager.fillStickerspecFromIdAndLength(grip, 3);
//                        float angle = PolygonManager.getTwistTotalAngle(grip.dim, node.dir);
//                        Math4d.get4dTwistMat(grip.coords, angle, incmat);
//                        Vec_h._MXM4i(current_matrix, current_matrix, incmat);
//                    }
//
//                    /*
//                     * We now have all the information we need;
//                     * delete the twists from the history
//                     */
//                    while (first_on_this_face != past_last_on_this_face) {
//                        deleteNode(first_on_this_face);
//                        first_on_this_face = first_on_this_face.next;
//                    }
//
//                    /*
//                     * Find a sticker on this face that's not
//                     * moved by the matrix; that will be the grip of the
//                     * concatenated move.
//                     */
//                    grip.face = face;
//                    for (grip.id_within_face = 0;
//                         grip.id_within_face < MagicCube.GRIPS_PER_FACE;
//                         grip.id_within_face++)
//                    {
//                        PolygonManager.fillStickerspecFromFaceAndIdAndLength(grip, 3);
//                        if (grip.dim >= 3)
//                            continue;
//                        Vec_h._VXM4(newcoords, grip.coords, current_matrix);
//                        if (Vec_h._EQVEC4(newcoords, grip.coords)) {
//                            /*
//                             * Found the right grip;
//                             * see if any of the following work:
//                             *  0 twists
//                             *  1 twist CW
//                             *  1 twist CCW
//                             *  2 twists in random direction
//                             */
//                            Vec_h._IDENTMAT4(testmat);
//                            if (Vec_h._EQMAT4(testmat, current_matrix)) {
//                                /*
//                                 * Result is 0 twists.
//                                 */
//                                break;
//                            }
//
//                            float angle = PolygonManager.getTwistTotalAngle(grip.dim, MagicCube.CCW);
//                            Math4d.get4dTwistMat(grip.coords, angle, testmat);
//                            if (Vec_h._EQMAT4(testmat, current_matrix)) {
//                                /*
//                                 * Result is 1 twist CCW.
//                                 */
//                                insertNode(past_last_on_this_face, grip.id_within_puzzle, MagicCube.CCW, 1);
//                                break;
//                            }
//                            angle = PolygonManager.getTwistTotalAngle(grip.dim, MagicCube.CW);
//                            Math4d.get4dTwistMat(grip.coords, angle, testmat);
//                            if (Vec_h._EQMAT4(testmat, current_matrix)) {
//                                /*
//                                 * Result is 1 twist CW.
//                                 */
//                                insertNode(past_last_on_this_face, grip.id_within_puzzle, MagicCube.CW, 1);
//                                break;
//                            }
//                            dir = Math.random() > 0.5 ? MagicCube.CW : MagicCube.CCW;
//                            angle = PolygonManager.getTwistTotalAngle(grip.dim, MagicCube.CCW);
//                            Math4d.get4dTwistMat(grip.coords, angle, testmat);
//                            Vec_h._MXM4i(testmat, testmat, testmat);
//                            if (Vec_h._EQMAT4(testmat, current_matrix)) {
//                                // Result is 2 twists
//                                insertNode(past_last_on_this_face, grip.id_within_puzzle, dir, 1);
//                                insertNode(past_last_on_this_face, grip.id_within_puzzle, dir, 1);
//                                break;
//                            }
//                            Assert(false);
//                        }
//                    }
//                    Assert(grip.id_within_face < 3 * 3 * 3);
//                }
//                first_on_this_face = past_last_on_this_face;
//            }
//            else
//                first_on_this_face = first_on_this_face.next;
//        }
//    }  // end oldcompress

    private static void print(History hist) {
    	for(Enumeration<MagicCube.TwistData> e=hist.moves(); e.hasMoreElements(); ) {
    		MagicCube.TwistData move = e.nextElement();
    		System.out.print(
    				move.grip.id_within_puzzle +
    				"," + move.direction +
    				"," + move.slicemask + " ");
    	}
    	System.out.println();
    }

    public static void main(String args[]) {
        History hist = new History(3);
        hist.append(1, 1, -1);
        hist.append(30, -1, 2);
        hist.append(100, 1, 1);
        try {
            System.out.println("before:");
            print(hist);
            OutputStreamWriter osw = new OutputStreamWriter(System.out);

            hist.write(osw);
            osw.flush();
            //hist.reverse();
            //hist.write(osw);
            //osw.flush();

            FileWriter fw;
            fw = new FileWriter("test.txt");
            hist.write(fw);
            fw.close();

            Reader fr = new FileReader("test.txt");
            PushbackReader pr = new PushbackReader(fr);
            hist.read(pr);
            pr.close();
            fr = pr = null;

            System.out.println("after write and read back:");
            hist.write(osw);
            osw.flush();

            System.out.println("reversed:");
            hist.reverse();
            print(hist);
            hist.write(osw);
            osw.flush();

            System.out.println("twice reversed:");
            hist.reverse();
            print(hist);
            hist.write(osw);
            osw.flush();

            System.out.println("thrice reversed:");
            hist.reverse();
            print(hist);
            hist.write(osw);
            osw.flush();
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(-1);
        }
//        for(java.util.Enumeration keys=System.getProperties().keys(); keys.hasMoreElements();) {
//            String key = (String)keys.nextElement();
//            System.out.println(key + " = " + System.getProperty(key));
//        }
    }

}
