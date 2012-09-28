/**
 * END USER LICENSE AGREEMENT (“EULA”)
 *
 * READ THIS AGREEMENT CAREFULLY (date: 9/13/2011):
 * http://www.akiban.com/licensing/20110913
 *
 * BY INSTALLING OR USING ALL OR ANY PORTION OF THE SOFTWARE, YOU ARE ACCEPTING
 * ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. YOU AGREE THAT THIS
 * AGREEMENT IS ENFORCEABLE LIKE ANY WRITTEN AGREEMENT SIGNED BY YOU.
 *
 * IF YOU HAVE PAID A LICENSE FEE FOR USE OF THE SOFTWARE AND DO NOT AGREE TO
 * THESE TERMS, YOU MAY RETURN THE SOFTWARE FOR A FULL REFUND PROVIDED YOU (A) DO
 * NOT USE THE SOFTWARE AND (B) RETURN THE SOFTWARE WITHIN THIRTY (30) DAYS OF
 * YOUR INITIAL PURCHASE.
 *
 * IF YOU WISH TO USE THE SOFTWARE AS AN EMPLOYEE, CONTRACTOR, OR AGENT OF A
 * CORPORATION, PARTNERSHIP OR SIMILAR ENTITY, THEN YOU MUST BE AUTHORIZED TO SIGN
 * FOR AND BIND THE ENTITY IN ORDER TO ACCEPT THE TERMS OF THIS AGREEMENT. THE
 * LICENSES GRANTED UNDER THIS AGREEMENT ARE EXPRESSLY CONDITIONED UPON ACCEPTANCE
 * BY SUCH AUTHORIZED PERSONNEL.
 *
 * IF YOU HAVE ENTERED INTO A SEPARATE WRITTEN LICENSE AGREEMENT WITH AKIBAN FOR
 * USE OF THE SOFTWARE, THE TERMS AND CONDITIONS OF SUCH OTHER AGREEMENT SHALL
 * PREVAIL OVER ANY CONFLICTING TERMS OR CONDITIONS IN THIS AGREEMENT.
 */

package com.akiban.qp.persistitadapter.indexcursor;

import com.akiban.ais.model.Column;
import com.akiban.ais.model.Index;
import com.akiban.ais.model.IndexColumn;
import com.akiban.qp.expression.BoundExpressions;
import com.akiban.qp.expression.IndexBound;
import com.akiban.qp.expression.IndexKeyRange;
import com.akiban.qp.operator.API;
import com.akiban.qp.operator.QueryContext;
import com.akiban.qp.persistitadapter.indexrow.PersistitIndexRow;
import com.akiban.qp.row.Row;
import com.akiban.server.api.dml.ColumnSelector;
import com.akiban.server.collation.AkCollator;
import com.akiban.server.types.AkType;
import com.akiban.server.types3.TInstance;
import com.akiban.server.types3.Types3Switch;
import com.akiban.server.types3.mcompat.mtypes.MNumeric;
import com.persistit.Key;
import com.persistit.exception.PersistitException;

import java.util.List;

class IndexCursorUnidirectional<S> extends IndexCursor
{
    // Cursor interface

    @Override
    public void open()
    {
        super.open();
        evaluateBoundaries(context, sortKeyAdapter);
        initializeForOpen();
    }

    @Override
    public Row next()
    {
        super.next();
        Row next = null;
        if (exchange() != null) {
            try {
                INDEX_TRAVERSE.hit();
                if (exchange().traverse(keyComparison, true)) {
                    next = row();
                    // Guard against bug 1046053
                    assert next != startKey;
                    assert next != endKey;
                    // If we're scanning a unique key index, then the row format has the declared key in the
                    // Persistit key, and undeclared hkey columns in the Persistit value. An index scan may actually
                    // restrict the entire declared key and leading hkeys fields. If this happens, then the first
                    // row found by exchange.traverse may actually not qualify -- those values may be lower than
                    // startKey. This can happen at most once per scan. pastStart indicates whether we have gotten
                    // past the startKey.
                    if (!pastStart) {
                        while (beforeStart(next)) {
                            next = null;
                            if (exchange().traverse(subsequentKeyComparison, true)) {
                                next = row();
                            } else {
                                close();
                            }
                        }
                        pastStart = true;
                    }
                    if (next != null && pastEnd(next)) {
                        next = null;
                        close();
                    }
                } else {
                    close();
                }
            } catch (PersistitException e) {
                close();
                adapter.handlePersistitException(e);
            }
        }
        keyComparison = subsequentKeyComparison;
        return next;
    }

    @Override
    public void close()
    {
        super.close();
        if (startKey != null) {
            clearStart();
        }
    }

    @Override
    public void destroy()
    {
        super.destroy();
        if (startKey != null) {
            adapter.returnIndexRow(startKey);
            startKey = null;
        }
        if (endKey != null) {
            adapter.returnIndexRow(endKey);
            endKey = null;
        }
    }

    @Override
    public void jump(Row row, ColumnSelector columnSelector)
    {
        assert keyRange != null;
        keyRange =
            direction == FORWARD
            ? keyRange.resetLo(new IndexBound(row, columnSelector))
            : keyRange.resetHi(new IndexBound(row, columnSelector));
        initializeCursor(keyRange, ordering);
        reevaluateBoundaries(context, sortKeyAdapter);
        initializeForOpen();
    }

    // IndexCursorUnidirectional interface

    public static <S> IndexCursorUnidirectional<S> create(QueryContext context,
                                                  IterationHelper iterationHelper,
                                                  IndexKeyRange keyRange,
                                                  API.Ordering ordering,
                                                  SortKeyAdapter<S, ?> sortKeyAdapter)
    {
        return
            keyRange == null // occurs if we're doing a Sort_Tree
            ? new IndexCursorUnidirectional<S>(context, iterationHelper, ordering, sortKeyAdapter)
            : new IndexCursorUnidirectional<S>(context, iterationHelper, keyRange, ordering, sortKeyAdapter);
    }

    // For use by this subclasses

    protected IndexCursorUnidirectional(QueryContext context,
                                        IterationHelper iterationHelper,
                                        IndexKeyRange keyRange,
                                        API.Ordering ordering,
                                        SortKeyAdapter<S, ?> sortKeyAdapter)
    {
        super(context, iterationHelper);
        // end state never changes. start state can change on a jump, so it is set in initializeCursor.
        this.endBoundColumns = keyRange.boundColumns();
        this.endKey = endBoundColumns == 0 ? null : adapter.takeIndexRow(keyRange.indexRowType());
        this.sortKeyAdapter = sortKeyAdapter;
        initializeCursor(keyRange, ordering);
    }

    protected void evaluateBoundaries(QueryContext context, SortKeyAdapter<S, ?> keyAdapter)
    {
        if (startKey != null) {
            if (startBoundColumns == 0) {
                startKey.append(startBoundary);
            } else {
                // Check constraints on start and end
                BoundExpressions loExpressions = lo.boundExpressions(context);
                BoundExpressions hiExpressions = hi.boundExpressions(context);
                for (int f = 0; f < endBoundColumns - 1; f++) {
                    keyAdapter.checkConstraints(loExpressions, hiExpressions, f, collators, tInstances);
                }
                /*
                    Null bounds are slightly tricky. An index restriction is described by an IndexKeyRange which contains
                    two IndexBounds. The IndexBound wraps an index row. The fields of the row that are being restricted are
                    described by the IndexBound's ColumnSelector. The only index restrictions supported specify:
                    a) equality for zero or more fields of the index,
                    b) 0-1 inequality, and
                    c) any remaining columns unbounded.

                    By the time we get here, we've stopped paying attention to part c. Parts a and b occupy the first
                    orderingColumns columns of the index. Now about the nulls: For each field of parts a and b, we have a
                    lo value and a hi value. There are four cases:

                    - both lo and hi are non-null: Just write the field values into startKey and endKey.

                    - lo is null: Write null into the startKey.

                    - hi is null, lo is not null: This restriction says that we want everything to the right of
                      the lo value. Persistit ranks null lower than anything, so instead of writing null to endKey,
                      we write Key.AFTER.

                    - lo and hi are both null: This is NOT an unbounded case. This means that we are restricting both
                      lo and hi to be null, so write null, not Key.AFTER to endKey.
                */
                // Construct start and end keys
                BoundExpressions startExpressions = start.boundExpressions(context);
                BoundExpressions endExpressions = end.boundExpressions(context);
                // startBoundColumns == endBoundColumns because jump() hasn't been called.
                // If it had we'd be in reevaluateBoundaries, not here.
                assert startBoundColumns == endBoundColumns;
                S[] startValues = keyAdapter.createSourceArray(startBoundColumns);
                S[] endValues = keyAdapter.createSourceArray(endBoundColumns);
                for (int f = 0; f < startBoundColumns; f++) {
                    startValues[f] = keyAdapter.get(startExpressions, f);
                    endValues[f] = keyAdapter.get(endExpressions, f);
                }
                clearStart();
                clearEnd();
                // Construct bounds of search. For first boundColumns - 1 columns, if start and end are both null,
                // interpret the nulls literally.
                int f = 0;
                while (f < startBoundColumns - 1) {
                    startKey.append(startValues[f], type(f), tInstance(f), collator(f));
                    endKey.append(startValues[f], type(f), tInstance(f), collator(f));
                    f++;
                }
                // For the last column:
                //  0   >   null      <   null:      (null, AFTER)
                //  1   >   null      <   non-null:  (null, end)
                //  2   >   null      <=  null:      Shouldn't happen
                //  3   >   null      <=  non-null:  (null, end]
                //  4   >   non-null  <   null:      (start, AFTER)
                //  5   >   non-null  <   non-null:  (start, end)
                //  6   >   non-null  <=  null:      Shouldn't happen
                //  7   >   non-null  <=  non-null:  (start, end]
                //  8   >=  null      <   null:      [null, AFTER)
                //  9   >=  null      <   non-null:  [null, end)
                // 10   >=  null      <=  null:      [null, null]
                // 11   >=  null      <=  non-null:  [null, end]
                // 12   >=  non-null  <   null:      [start, AFTER)
                // 13   >=  non-null  <   non-null:  [start, end)
                // 14   >=  non-null  <=  null:      Shouldn't happen
                // 15   >=  non-null  <=  non-null:  [start, end]
                //
                if (direction == FORWARD) {
                    // Start values
                    startKey.append(startValues[f], type(f), tInstance(f), collator(f));
                    // End values
                    if (keyAdapter.isNull(endValues[f])) {
                        if (endInclusive) {
                            if (startInclusive && keyAdapter.isNull(startValues[f])) {
                                // Case 10:
                                endKey.append(endValues[f], type(f), tInstance(f), collator(f));
                            } else {
                                // Cases 2, 6, 14:
                                throw new IllegalArgumentException();
                            }
                        } else {
                            // Cases 0, 4, 8, 12
                            endKey.append(Key.AFTER);
                        }
                    } else {
                        // Cases 1, 3, 5, 7, 9, 11, 13, 15
                        endKey.append(endValues[f], type(f), tInstance(f), collator(f));
                    }
                } else {
                    // Same as above, swapping start and end
                    // End values
                    endKey.append(endValues[f], type(f), tInstance(f), collator(f));
                    // Start values
                    if (keyAdapter.isNull(startValues[f])) {
                        if (startInclusive) {
                            if (endInclusive && keyAdapter.isNull(endValues[f])) {
                                // Case 10:
                                startKey.append(startValues[f], type(f), tInstance(f), collator(f));
                            } else {
                                // Cases 2, 6, 14:
                                throw new IllegalArgumentException();
                            }
                        } else {
                            // Cases 0, 4, 8, 12
                            startKey.append(Key.AFTER);
                        }
                    } else {
                        // Cases 1, 3, 5, 7, 9, 11, 13, 15
                        startKey.append(startValues[f], type(f), tInstance(f), collator(f));
                    }
                }
            }
        }
    }

    // A lot like evaluateBoundaries, but simplified because end state can be left alone.
    protected void reevaluateBoundaries(QueryContext context, SortKeyAdapter<S, ?> keyAdapter)
    {
        if (startBoundColumns == 0) {
            startKey.append(startBoundary);
        } else {
            // Construct start key
            BoundExpressions startExpressions = start.boundExpressions(context);
            S[] startValues = keyAdapter.createSourceArray(startBoundColumns);
            for (int f = 0; f < startBoundColumns; f++) {
                startValues[f] = keyAdapter.get(startExpressions, f);
            }
            clearStart();
            // Construct bounds of search. For first boundColumns - 1 columns, if start and end are both null,
            // interpret the nulls literally.
            int f = 0;
            while (f < startBoundColumns - 1) {
                startKey.append(startValues[f], type(f), tInstance(f), collator(f));
                f++;
            }
            if (direction == FORWARD) {
                startKey.append(startValues[f], type(f), tInstance(f), collator(f));
            } else {
                if (keyAdapter.isNull(startValues[f])) {
                    if (startInclusive) {
                        // Assume case 10, the only valid choice here. On evaluateBoundaries, cases 2, 6, 14
                        // would have thrown IllegalArgumentException.
                        startKey.append(startValues[f], type(f), tInstance(f), collator(f));
                    } else {
                        // Cases 0, 4, 8, 12
                        startKey.append(Key.AFTER);
                    }
                } else {
                    // Cases 1, 3, 5, 7, 9, 11, 13, 15
                    startKey.append(startValues[f], type(f), tInstance(f), collator(f));
                }
            }
        }
    }

    protected boolean beforeStart(Row row)
    {
        boolean beforeStart = false;
        if (startKey != null && row != null) {
            PersistitIndexRow current = (PersistitIndexRow) row;
            int c = current.compareTo(startKey) * direction;
            beforeStart = c < 0 || c == 0 && !startInclusive;
        }
        return beforeStart;
    }

    protected boolean pastEnd(Row row)
    {
        boolean pastEnd;
        if (endKey == null) {
            pastEnd = false;
        } else {
            PersistitIndexRow current = (PersistitIndexRow) row;
            int c = current.compareTo(endKey) * direction;
            pastEnd = c > 0 || c == 0 && !endInclusive;
        }
        return pastEnd;
    }

    protected void clearStart()
    {
        startKeyKey.clear();
        startKey.resetForWrite(index(), startKeyKey, null);
    }

    protected void clearEnd()
    {
        endKeyKey.clear();
        endKey.resetForWrite(index(), endKeyKey, null);
    }

    protected AkType type(int f)
    {
        return types == null ? null : types[f];
    }

    protected TInstance tInstance(int f)
    {
        return tInstances == null ? null : tInstances[f];
    }

    protected AkCollator collator(int f)
    {
        return collators == null ? null : collators[f];
    }

    // For use by this class

    private void initializeCursor(IndexKeyRange keyRange, API.Ordering ordering)
    {
        this.keyRange = keyRange;
        this.ordering = ordering;
        this.lo = keyRange.lo();
        this.hi = keyRange.hi();
        if (ordering.allAscending()) {
            this.direction = FORWARD;
            this.start = this.lo;
            this.startInclusive = keyRange.loInclusive();
            this.end = this.hi;
            this.endInclusive = keyRange.hiInclusive();
            this.initialKeyComparison = startInclusive ? Key.GTEQ : Key.GT;
            this.subsequentKeyComparison = Key.GT;
            this.startBoundary = Key.BEFORE;
        } else if (ordering.allDescending()) {
            this.direction = BACKWARD;
            this.start = this.hi;
            this.startInclusive = keyRange.hiInclusive();
            this.end = this.lo;
            this.endInclusive = keyRange.loInclusive();
            this.initialKeyComparison = startInclusive ? Key.LTEQ : Key.LT;
            this.subsequentKeyComparison = Key.LT;
            this.startBoundary = Key.AFTER;
        } else {
            assert false : ordering;
        }
        this.startKey = adapter.takeIndexRow(keyRange.indexRowType());
        this.startKeyKey = adapter.newKey();
        this.endKeyKey = adapter.newKey();
        this.startBoundColumns = keyRange.boundColumns();
        this.types = sortKeyAdapter.createAkTypes(startBoundColumns);
        this.collators = sortKeyAdapter.createAkCollators(startBoundColumns);
        this.tInstances = sortKeyAdapter.createTInstances(startBoundColumns);
        if (keyRange.indexRowType().index().isSpatial()) {
            // This is a cursor created on behalf of a spatial index. There should be only one key column,
            // a z-value of type long.
            if (startBoundColumns == 1) {
                if (Types3Switch.ON) {
                    tInstances[0] = MNumeric.BIGINT.instance();
                }
                else {
                    types[0] = AkType.LONG;
                    collators[0] = null;
                }
            } else {
                // Unbounded scan of spatial index
                assert startBoundColumns == 0;
            }
        } else {
            List<IndexColumn> indexColumns = index().getAllColumns();
            for (int f = 0; f < startBoundColumns; f++) {
                Column column = indexColumns.get(f).getColumn();
                sortKeyAdapter.setColumnMetadata(column, f, types, collators, tInstances);
            }
        }
    }

    private void initializeForOpen()
    {
        exchange().clear();
        if (startKey != null) {
            // boundColumns > 0 means that startKey has some values other than BEFORE or AFTER. start == null
            // could happen in a lexicographic scan, and indicates no lower bound (so we're starting at BEFORE or AFTER).
            if ((startBoundColumns > 0 && start != null) &&
                (direction == FORWARD && !startInclusive || direction == BACKWARD && startInclusive)) {
                // - direction == FORWARD && !startInclusive: If the search key is (10, 5) and there are
                //   rows (10, 5, ...) then we do not want them if !startInclusive. Making the search key
                //   (10, 5, AFTER) will cause these records to be skipped.
                // - direction == BACKWARD && startInclusive: Similarly, going in the other direction, we do the
                //   (10, 5, ...) records if startInclusive. But an LTEQ traversal would miss it unless we search
                //   for (10, 5, AFTER).
                startKey.append(Key.AFTER);
            }
            // Copy just the persistit key part of startKey to the exchange's key. startKey may be overspecified.
            // E.g., if we have a PK index for a non-root table, the index row is [childPK, parentPK], and an index
            // scan may specify a value for both. But the persistit search can only deal with the [childPK] part of
            // the traversal.
            startKey.copyPersistitKeyTo(exchange().getKey());
            pastStart = false;
        }
        keyComparison = initialKeyComparison;
    }

    private Index index()
    {
        return keyRange.indexRowType().index();
    }

    private IndexCursorUnidirectional(QueryContext context,
                                      IterationHelper iterationHelper,
                                      API.Ordering ordering,
                                      SortKeyAdapter<S, ?> sortKeyAdapter)
    {
        super(context, iterationHelper);
        this.keyRange = null;
        this.ordering = ordering;
        if (ordering.allAscending()) {
            this.startBoundary = Key.BEFORE;
            this.initialKeyComparison = Key.GT;
            this.subsequentKeyComparison = Key.GT;
        } else if (ordering.allDescending()) {
            this.startBoundary = Key.AFTER;
            this.initialKeyComparison = Key.LT;
            this.subsequentKeyComparison = Key.LT;
        } else {
            assert false : ordering;
        }
        this.startKey = null;
        this.endKey = null;
        this.startBoundColumns = 0;
        this.endBoundColumns = 0;
        this.sortKeyAdapter = sortKeyAdapter;
    }

    // Class state

    private static final int FORWARD = 1;
    private static final int BACKWARD = -1;

    // Object state

    private IndexKeyRange keyRange;
    private API.Ordering ordering;
    protected int direction; // +1 = ascending, -1 = descending
    protected Key.Direction keyComparison;
    protected Key.Direction initialKeyComparison;
    protected Key.Direction subsequentKeyComparison;
    protected Key.EdgeValue startBoundary; // Start of a scan that is unbounded at the start
    // start/endBoundColumns is the number of index fields with restrictions. They start out having the same value.
    // But jump(Row) resets state pertaining to the start of a scan, including startBoundColumns.
    protected int startBoundColumns;
    protected int endBoundColumns;
    protected AkType[] types;
    protected TInstance[] tInstances;
    protected AkCollator[] collators;
    protected IndexBound lo;
    protected IndexBound hi;
    protected IndexBound start;
    protected IndexBound end;
    protected boolean startInclusive;
    protected boolean endInclusive;
    protected PersistitIndexRow startKey;
    protected PersistitIndexRow endKey;
    private Key startKeyKey;
    private Key endKeyKey;
    private boolean pastStart;
    private SortKeyAdapter<S, ?> sortKeyAdapter;
}
