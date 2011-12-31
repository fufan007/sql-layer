/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.akiban.server.expression.std;

import java.util.List;
import java.util.ArrayList;
import com.akiban.server.error.InvalidArgumentTypeException;
import com.akiban.server.expression.ExpressionComposer;
import com.akiban.server.types.extract.LongExtractor;
import com.akiban.server.types.extract.Extractors;
import com.akiban.server.expression.Expression;
import com.akiban.server.types.AkType;
import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.*;

public class DateTimeArithExpressionTest extends ComposedExpressionTestBase
{
    LongExtractor extractor = Extractors.getLongExtractor(AkType.DATE);
    
    @Test
    public void testDateAdd ()
    {       
        Expression left1 = new LiteralExpression(AkType.DATE, extractor.getLong("2009-12-12"));
        Expression right1 = new LiteralExpression(AkType.LONG, 12L); // second arg is a LONG
        
        Expression left2 = new LiteralExpression(AkType.DATE, extractor.getLong("2009-12-24"));
        
        // top1 is a DATE: ADDDATE('2009-12-12', 12)
        Expression top1 = DateTimeArithExpression.ADD_DATE_COMPOSER.compose(Arrays.asList(left1, right1));
        
        // top2 is an interval
        Expression top2 = ArithOps.MINUS.compose(Arrays.asList(left2, left1));
        
        // top3 is a DATE: ADDDATE("2009-12-12", interval 12 days)
        Expression top3 = DateTimeArithExpression.ADD_DATE_COMPOSER.compose(Arrays.asList(left1, top2));
        
        assertEquals("assert top1 == 2009-12-24", extractor.getLong("2009-12-24"),top1.evaluation().eval().getDate());
        assertEquals("assert top3 == top1", top1.evaluation().eval().getDate(), top3.evaluation().eval().getDate());
    }
    
    @Test
    public void testDateAdd_IntervalMonth ()
    {   
        List<AkType> argType = new ArrayList<AkType>();
        argType.add(AkType.DATE);
        argType.add(AkType.INTERVAL_MONTH);
        
        List<AkType> newArgType = new ArrayList<AkType>(argType);
        
        //test argumentTypes ()
        DateTimeArithExpression.ADD_DATE_COMPOSER.argumentTypes(newArgType);
        for (int n = 0; n < argType.size(); ++n)
            assertTrue("Assert type list doesn't get changed", argType.get(n) == newArgType.get(n));
        
        
        Expression left = new LiteralExpression(AkType.DATE, extractor.getLong("2008-02-29"));
        Expression right = new LiteralExpression(AkType.INTERVAL_MONTH, 12L);
        
        Expression top = DateTimeArithExpression.ADD_DATE_COMPOSER.compose(Arrays.asList(left, right));
        assertEquals(extractor.getLong("2009-02-28"), top.evaluation().eval().getDate());
    }
    
    @Test
    public void testTimeDiff ()
    {
        Expression l = new LiteralExpression(AkType.TIME,100915L);
        Expression r = new LiteralExpression(AkType.TIME, 90915L);

        Expression top = DateTimeArithExpression.TIMEDIFF_COMPOSER.compose(Arrays.asList(l,r));
        long actual = top.evaluation().eval().getTime();

        assertEquals(10000L, actual);
    }

    @Test
    public void testDateTimeDiff()
    {
        Expression l = new LiteralExpression(AkType.DATETIME, 20091010123010L);
        Expression r = new LiteralExpression(AkType.DATETIME, 20091010123001L);

        Expression top = DateTimeArithExpression.TIMEDIFF_COMPOSER.compose(Arrays.asList(r,l));
        long actual = top.evaluation().eval().getTime();

        assertEquals(-9L, actual);
    }

    @Test
    public void testTimeStampDiff ()
    {
        Expression l = new LiteralExpression(AkType.TIMESTAMP,
                Extractors.getLongExtractor(AkType.TIMESTAMP).getLong("2009-12-10 10:10:10"));
        Expression r = new LiteralExpression(AkType.TIMESTAMP,
                Extractors.getLongExtractor(AkType.TIMESTAMP).getLong("2009-12-11 10:09:09"));

        Expression top = DateTimeArithExpression.TIMEDIFF_COMPOSER.compose(Arrays.asList(l,r));
        long actual = top.evaluation().eval().getTime();

        assertEquals(-235859L, actual);
    }

    @Test
    public void testNullTimeDiff ()
    {
        Expression l = LiteralExpression.forNull();
        Expression r = new LiteralExpression(AkType.TIME, 1234L);

        Expression top = DateTimeArithExpression.TIMEDIFF_COMPOSER.compose(Arrays.asList(l,r));
        assertTrue ("top is null", top.evaluation().eval().isNull());
    }

    @Test
    public void testDateDiff ()
    {
        test("2011-12-05", "2011-11-01", 34L);
        test("2009-12-10", "2010-01-12", -33L);
        test("2008-02-01", "2008-03-01", -29L);
        test("2010-01-01", "2010-01-01", 0L);
    }

    @Test
    public void testNullDateiff()
    {
        Expression l = LiteralExpression.forNull();
        Expression r = new LiteralExpression(AkType.DATE, 1234L);

        Expression top = DateTimeArithExpression.DATEDIFF_COMPOSER.compose(Arrays.asList(l, r));
        assertTrue("top is null", top.evaluation().eval().isNull());
    }

    @Test(expected = InvalidArgumentTypeException.class)
    public void testInvalidArgumenTypeDateDiff ()
    {
        Expression l = new LiteralExpression(AkType.DATE, 1234L);
        Expression r = new LiteralExpression(AkType.DATETIME, 1234L);

         Expression top = DateTimeArithExpression.DATEDIFF_COMPOSER.compose(Arrays.asList(l, r));
    }

    @Test(expected = InvalidArgumentTypeException.class)
    public void testInvalidArgumenTypeTimeDiff ()
    {
        Expression l = new LiteralExpression(AkType.DATE, 1234L);
        Expression r = new LiteralExpression(AkType.DATETIME, 1234L);

         Expression top = DateTimeArithExpression.TIMEDIFF_COMPOSER.compose(Arrays.asList(l, r));
    }

    @Override
    protected CompositionTestInfo getTestInfo()
    {
        return new CompositionTestInfo(2, AkType.DATE, true);
    }

    @Override
    protected ExpressionComposer getComposer()
    {
        return DateTimeArithExpression.DATEDIFF_COMPOSER;
    }

    @Override
    protected boolean alreadyExc()
    {
        return false;
    }
    
    // ---------------- private method -----------------
        
    private static void test (String left, String right, long expected)
    {
        LongExtractor ex = Extractors.getLongExtractor(AkType.DATE);
        Expression l = new LiteralExpression(AkType.DATE,ex.getLong(left));
        Expression r = new LiteralExpression(AkType.DATE, ex.getLong(right));

        Expression top = DateTimeArithExpression.DATEDIFF_COMPOSER.compose(Arrays.asList(l,r));
        long actual = top.evaluation().eval().getLong();

        assertEquals(expected, actual);
    }
}