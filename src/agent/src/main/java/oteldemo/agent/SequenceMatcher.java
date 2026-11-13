/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Port of Python's {@code difflib.SequenceMatcher(None, a, b).ratio()} (with the default {@code
 * autojunk=True} heuristic), used by the VCR cassette matcher so the same requests clear the same
 * similarity threshold as in the Python agent.
 */
final class SequenceMatcher {

  private final int[] a;
  private final int[] b;
  private final Map<Integer, int[]> b2j = new HashMap<>();

  SequenceMatcher(String a, String b) {
    this.a = a.codePoints().toArray();
    this.b = b.codePoints().toArray();
    chainB();
  }

  static double ratio(String a, String b) {
    return new SequenceMatcher(a, b).ratio();
  }

  private void chainB() {
    Map<Integer, List<Integer>> positions = new HashMap<>();
    for (int i = 0; i < b.length; i++) {
      positions.computeIfAbsent(b[i], k -> new ArrayList<>()).add(i);
    }
    int n = b.length;
    Set<Integer> popular = new HashSet<>();
    if (n >= 200) {
      int ntest = n / 100 + 1;
      for (Map.Entry<Integer, List<Integer>> e : positions.entrySet()) {
        if (e.getValue().size() > ntest) {
          popular.add(e.getKey());
        }
      }
    }
    for (Map.Entry<Integer, List<Integer>> e : positions.entrySet()) {
      if (popular.contains(e.getKey())) {
        continue;
      }
      b2j.put(e.getKey(), e.getValue().stream().mapToInt(Integer::intValue).toArray());
    }
  }

  private record Match(int i, int j, int size) {}

  private Match findLongestMatch(int alo, int ahi, int blo, int bhi) {
    int besti = alo;
    int bestj = blo;
    int bestsize = 0;
    Map<Integer, Integer> j2len = new HashMap<>();
    for (int i = alo; i < ahi; i++) {
      Map<Integer, Integer> newj2len = new HashMap<>();
      int[] js = b2j.get(a[i]);
      if (js != null) {
        for (int j : js) {
          if (j < blo) {
            continue;
          }
          if (j >= bhi) {
            break;
          }
          int k = j2len.getOrDefault(j - 1, 0) + 1;
          newj2len.put(j, k);
          if (k > bestsize) {
            besti = i - k + 1;
            bestj = j - k + 1;
            bestsize = k;
          }
        }
      }
      j2len = newj2len;
    }
    // extend the match over "popular" elements that were removed from b2j (no junk set here)
    while (besti > alo && bestj > blo && a[besti - 1] == b[bestj - 1]) {
      besti--;
      bestj--;
      bestsize++;
    }
    while (besti + bestsize < ahi
        && bestj + bestsize < bhi
        && a[besti + bestsize] == b[bestj + bestsize]) {
      bestsize++;
    }
    return new Match(besti, bestj, bestsize);
  }

  double ratio() {
    int la = a.length;
    int lb = b.length;
    if (la + lb == 0) {
      return 1.0;
    }
    int matches = 0;
    Deque<int[]> queue = new ArrayDeque<>();
    queue.push(new int[] {0, la, 0, lb});
    while (!queue.isEmpty()) {
      int[] q = queue.pop();
      int alo = q[0];
      int ahi = q[1];
      int blo = q[2];
      int bhi = q[3];
      Match m = findLongestMatch(alo, ahi, blo, bhi);
      if (m.size() > 0) {
        matches += m.size();
        if (alo < m.i() && blo < m.j()) {
          queue.push(new int[] {alo, m.i(), blo, m.j()});
        }
        if (m.i() + m.size() < ahi && m.j() + m.size() < bhi) {
          queue.push(new int[] {m.i() + m.size(), ahi, m.j() + m.size(), bhi});
        }
      }
    }
    return 2.0 * matches / (la + lb);
  }
}
