/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.accumulo.core.client.mapreduce;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.List;

import org.apache.accumulo.bsp.AccumuloInputFormat;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.client.mapreduce.InputFormatBase.AccumuloIterator;
import org.apache.accumulo.core.client.mapreduce.InputFormatBase.AccumuloIteratorOption;
import org.apache.accumulo.core.client.mock.MockInstance;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.iterators.user.RegExFilter;
import org.apache.accumulo.core.iterators.user.WholeRowIterator;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hama.HamaConfiguration;
import org.apache.hama.bsp.BSP;
import org.apache.hama.bsp.BSPJob;
import org.apache.hama.bsp.BSPPeer;
import org.apache.hama.bsp.InputSplit;
import org.apache.hama.bsp.sync.SyncException;
import org.apache.hama.util.KeyValuePair;
import org.junit.After;
import org.junit.Test;

public class AccumuloInputFormatTest {
  
  @After
  public void tearDown() throws Exception {}
  
  /**
   * Test basic setting & getting of max versions.
   * 
   * @throws IOException
   *           Signals that an I/O exception has occurred.
   */
  @Test
  public void testMaxVersions() throws IOException {
    BSPJob job = new BSPJob();
    AccumuloInputFormat.setMaxVersions(job.getConf(), 1);
    int version = AccumuloInputFormat.getMaxVersions(job.getConf());
    assertEquals(1, version);
  }
  
  @Test(expected = IOException.class)
  public void testMaxVersionsLessThan1() throws IOException {
    BSPJob job = new BSPJob();
    AccumuloInputFormat.setMaxVersions(job.getConf(), 0);
  }
  
  @Test
  public void testNoMaxVersion() throws IOException {
    BSPJob job = new BSPJob();
    assertEquals(-1, AccumuloInputFormat.getMaxVersions(job.getConf()));
  }
  
  @Test
  public void testSetIterator() throws IOException {
    BSPJob job = new BSPJob();
    
    AccumuloInputFormat.addIterator(job.getConf(), new IteratorSetting(1, "WholeRow", "org.apache.accumulo.core.iterators.WholeRowIterator"));
    Configuration conf = job.getConf();
    String iterators = conf.get("AccumuloInputFormat.iterators");
    assertEquals("1:org.apache.accumulo.core.iterators.WholeRowIterator:WholeRow", iterators);
  }
  
  @Test
  public void testAddIterator() throws IOException {
    BSPJob job = new BSPJob();
    
    AccumuloInputFormat.addIterator(job.getConf(), new IteratorSetting(1, "WholeRow", WholeRowIterator.class));
    AccumuloInputFormat.addIterator(job.getConf(), new IteratorSetting(2, "Versions", "org.apache.accumulo.core.iterators.VersioningIterator"));
    IteratorSetting iter = new IteratorSetting(3, "Count", "org.apache.accumulo.core.iterators.CountingIterator");
    iter.addOption("v1", "1");
    iter.addOption("junk", "\0omg:!\\xyzzy");
    AccumuloInputFormat.addIterator(job.getConf(), iter);
    
    List<AccumuloIterator> list = AccumuloInputFormat.getIterators(job.getConf());
    
    // Check the list size
    assertTrue(list.size() == 3);
    
    // Walk the list and make sure our settings are correct
    AccumuloIterator setting = list.get(0);
    assertEquals(1, setting.getPriority());
    assertEquals("org.apache.accumulo.core.iterators.user.WholeRowIterator", setting.getIteratorClass());
    assertEquals("WholeRow", setting.getIteratorName());
    
    setting = list.get(1);
    assertEquals(2, setting.getPriority());
    assertEquals("org.apache.accumulo.core.iterators.VersioningIterator", setting.getIteratorClass());
    assertEquals("Versions", setting.getIteratorName());
    
    setting = list.get(2);
    assertEquals(3, setting.getPriority());
    assertEquals("org.apache.accumulo.core.iterators.CountingIterator", setting.getIteratorClass());
    assertEquals("Count", setting.getIteratorName());
    
    List<AccumuloIteratorOption> iteratorOptions = AccumuloInputFormat.getIteratorOptions(job.getConf());
    assertEquals(2, iteratorOptions.size());
    assertEquals("Count", iteratorOptions.get(0).getIteratorName());
    assertEquals("Count", iteratorOptions.get(1).getIteratorName());
    assertEquals("v1", iteratorOptions.get(0).getKey());
    assertEquals("1", iteratorOptions.get(0).getValue());
    assertEquals("junk", iteratorOptions.get(1).getKey());
    assertEquals("\0omg:!\\xyzzy", iteratorOptions.get(1).getValue());
  }
  
  @Test
  public void testIteratorOptionEncoding() throws Throwable {
    String key = "colon:delimited:key";
    String value = "comma,delimited,value";
    IteratorSetting someSetting = new IteratorSetting(1, "iterator", "Iterator.class");
    someSetting.addOption(key, value);
    BSPJob job = new BSPJob();
    AccumuloInputFormat.addIterator(job.getConf(), someSetting);
    
    final String rawConfigOpt = new AccumuloIteratorOption("iterator", key, value).toString();
    
    assertEquals(rawConfigOpt, job.getConf().get("AccumuloInputFormat.iterators.options"));
    
    List<AccumuloIteratorOption> opts = AccumuloInputFormat.getIteratorOptions(job.getConf());
    assertEquals(1, opts.size());
    assertEquals(opts.get(0).getKey(), key);
    assertEquals(opts.get(0).getValue(), value);
    
    someSetting.addOption(key + "2", value);
    someSetting.setPriority(2);
    someSetting.setName("it2");
    AccumuloInputFormat.addIterator(job.getConf(), someSetting);
    opts = AccumuloInputFormat.getIteratorOptions(job.getConf());
    assertEquals(3, opts.size());
    for (AccumuloIteratorOption opt : opts) {
      assertEquals(opt.getKey().substring(0, key.length()), key);
      assertEquals(opt.getValue(), value);
    }
  }
  
  @Test
  public void testGetIteratorSettings() throws IOException {
    BSPJob job = new BSPJob();
    
    AccumuloInputFormat.addIterator(job.getConf(), new IteratorSetting(1, "WholeRow", "org.apache.accumulo.core.iterators.WholeRowIterator"));
    AccumuloInputFormat.addIterator(job.getConf(), new IteratorSetting(2, "Versions", "org.apache.accumulo.core.iterators.VersioningIterator"));
    AccumuloInputFormat.addIterator(job.getConf(), new IteratorSetting(3, "Count", "org.apache.accumulo.core.iterators.CountingIterator"));
    
    List<AccumuloIterator> list = AccumuloInputFormat.getIterators(job.getConf());
    
    // Check the list size
    assertTrue(list.size() == 3);
    
    // Walk the list and make sure our settings are correct
    AccumuloIterator setting = list.get(0);
    assertEquals(1, setting.getPriority());
    assertEquals("org.apache.accumulo.core.iterators.WholeRowIterator", setting.getIteratorClass());
    assertEquals("WholeRow", setting.getIteratorName());
    
    setting = list.get(1);
    assertEquals(2, setting.getPriority());
    assertEquals("org.apache.accumulo.core.iterators.VersioningIterator", setting.getIteratorClass());
    assertEquals("Versions", setting.getIteratorName());
    
    setting = list.get(2);
    assertEquals(3, setting.getPriority());
    assertEquals("org.apache.accumulo.core.iterators.CountingIterator", setting.getIteratorClass());
    assertEquals("Count", setting.getIteratorName());
    
  }
  
  @Test
  public void testSetRegex() throws IOException {
    BSPJob job = new BSPJob();
    
    String regex = ">\"*%<>\'\\";
    
    IteratorSetting is = new IteratorSetting(50, regex, RegExFilter.class);
    RegExFilter.setRegexs(is, regex, null, null, null, false);
    AccumuloInputFormat.addIterator(job.getConf(), is);
    
    assertTrue(regex.equals(AccumuloInputFormat.getIterators(job.getConf()).get(0).getIteratorName()));
  }
  
  static class TestBSP extends BSP<Key,Value,Key,Value> {
    Key key = null;
    int count = 0;
    
    @Override
    public void bsp(BSPPeer<Key,Value,Key,Value> peer) throws IOException, SyncException, InterruptedException {
      // this method reads the next key value record from file
      KeyValuePair<Key,Value> pair;
      
      while ((pair = peer.readNext()) != null) {
        if (key != null) {
          assertEquals(key.getRow().toString(), new String(pair.getValue().get()));
        }
        
        assertEquals(pair.getKey().getRow(), new Text(String.format("%09x", count + 1)));
        assertEquals(new String(pair.getValue().get()), String.format("%09x", count));
        count++;
        
        key = new Key(pair.getKey());
      }
      
      peer.sync();
      assertEquals(100, count);
    }
  }
  
  @Test
  public void testBsp() throws Exception {
    MockInstance mockInstance = new MockInstance("testmapinstance");
    Connector c = mockInstance.getConnector("root", new byte[] {});
    if (c.tableOperations().exists("testtable"))
      c.tableOperations().delete("testtable");
    c.tableOperations().create("testtable");
    
    BatchWriter bw = c.createBatchWriter("testtable", 10000L, 1000L, 4);
    for (int i = 0; i < 100; i++) {
      Mutation m = new Mutation(new Text(String.format("%09x", i + 1)));
      m.put(new Text(), new Text(), new Value(String.format("%09x", i).getBytes()));
      bw.addMutation(m);
    }
    bw.close();
    
    BSPJob job = new BSPJob(new HamaConfiguration());
    job.setInputFormat(AccumuloInputFormat.class);
    job.setBspClass(TestBSP.class);
    job.setInputPath(new Path("test"));
    AccumuloInputFormat.setInputInfo(job.getConf(), "root", "".getBytes(), "testtable", new Authorizations());
    AccumuloInputFormat.setMockInstance(job.getConf(), "testmapinstance");
    
    AccumuloInputFormat input = new AccumuloInputFormat();
    InputSplit[] splits = input.getSplits(job, 0);
    assertEquals(splits.length, 1);
    
    job.waitForCompletion(false);
  }
}
