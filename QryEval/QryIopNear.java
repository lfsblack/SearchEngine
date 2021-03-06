/**
 * Copyright (c) 2017, Carnegie Mellon University.  All Rights Reserved.
 */

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Vector;

/**
 *  The Near operator for all retrieval models.
 */
public class QryIopNear extends QryIop {
    // The distance between words
    private int n;

    public QryIopNear(int n) {
        this.n = n;
    }

    /**
     *  Evaluate the query operator; the result is an internal inverted
     *  list that may be accessed via the internal iterators.
     *  @throws IOException Error accessing the Lucene index.
     */
    protected void evaluate() throws IOException {
        this.invertedList = new InvList(this.getField());

        if (args.size() < 2) {
            return;
        }

        int size = this.args.size();
        QryIop[] qryIops = new QryIop[size];
        for(int i = 0; i < size; i++) {
            qryIops[i] = (QryIop) this.args.get(i);
        }


        // Each loop, process one doc
        while (this.docIteratorHasMatchAll(null)) {
            // find match on DocId
            // This is really buggy !!! Only get docId from the args
            // Don't use docId = this.docIteratorGetMatch();
            int docId = this.args.get(0).docIteratorGetMatch();
            for (int i = 0; i < this.args.size(); i++) {
                this.args.get(i).docIteratorAdvanceTo(docId);
            }

            // Iterate over the position, record all the location to positions
            List<Integer> positions = new ArrayList<Integer>();
            int[] locs = new int[size];

            // Each loop, add one location to position
            while (true) {
                Boolean breakFlag = false;
                Boolean matchFlag = true;

                // record the current match location
                // If any QryIopTerm reaches the end of position, break;
                for(int i = 0; i < size; i++) {
                    if (!qryIops[i].locIteratorHasMatch()) {
                        breakFlag = true;
                        break;
                    } else {
                        locs[i] = qryIops[i].locIteratorGetMatch();
                    }
                }
                if (breakFlag) { break;}

                // check igitf the locations are near
                for(int i = 0; i < size - 1; i++) {
                    if (!( locs[i + 1] - locs[i] > 0 && locs[i + 1] - locs[i] <= n)) {
                        matchFlag = false;
                        break;
                    }
                }

                // If Match, record the position and advance all the pointers
                // If not match. advance the smallest pointer
                if (matchFlag) {
                    positions.add(locs[size - 1]);
                    for (int i = 0; i < size; i++) {
                        qryIops[i].locIteratorAdvance();
                    }
                } else {
                    int minidx = minIdx(locs);
                    qryIops[minidx].locIteratorAdvance();
                }
            }


            if (positions.size() > 0) {
                this.invertedList.appendPosting(docId, positions);
            }
            for (int i = 0; i < this.args.size(); i++) {
                this.args.get(i).docIteratorAdvancePast(docId);
            }
        }  // if we don't have next match document, All process done
    }


    private int minIdx(int[] nums) {
        int mindidx = 0;
        for (int i = 1; i < nums.length; i++) {
            if (nums[mindidx] > nums[i]) {
                mindidx = i;
            }
        }
        return mindidx;
    }

}
