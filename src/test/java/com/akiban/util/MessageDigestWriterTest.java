/**
 * Copyright (C) 2009-2013 Akiban Technologies, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.akiban.util;

import static org.junit.Assert.*;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;

import org.junit.Test;

public class MessageDigestWriterTest {

    
    @Test
    public void testGetDigest() throws NoSuchAlgorithmException {
        @SuppressWarnings("resource")
        MessageDigestWriter writer = new MessageDigestWriter();
        assertNotNull(writer.getDigest());
    }

    @Test
    public void testToString() throws IOException, NoSuchAlgorithmException {
        String test = "This is a test string";
        MessageDigestWriter writer = new MessageDigestWriter();
        writer.write(test);
        writer.close();
        assertEquals(writer.toString(), "9a5b26fef4c9f4fb07181f3e4efbc986");
    }
    
    @Test
    public void testoffset()  throws IOException, NoSuchAlgorithmException {
        // Test my off-by-one problem is not present here
        String test1 = "This is a test string";
        String test2 =           "test string";

        MessageDigestWriter writerA = new MessageDigestWriter();
        
        writerA.write(test2);
        writerA.close();
        String digest1 = writerA.toString();
        
        MessageDigestWriter writerB = new MessageDigestWriter();
        char[] chars = test1.toCharArray();
        writerB.write(chars, 10, 11);
        writerB.close();
        String digest2 = writerB.toString();
        assertEquals(digest1, "94528964c80801e2826a50d617d8ed76");
        assertEquals(digest1, digest2);
               
    }

}
