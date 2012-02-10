// Copyright 2012 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.hughes.android.dictionary.parser.wiktionary;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.hughes.android.dictionary.engine.EntryTypeName;
import com.hughes.android.dictionary.engine.IndexBuilder;
import com.hughes.android.dictionary.engine.IndexedEntry;
import com.hughes.android.dictionary.engine.PairEntry;
import com.hughes.android.dictionary.engine.PairEntry.Pair;
import com.hughes.android.dictionary.parser.WikiTokenizer;

public final class EnForeignParser extends EnParser {

    public EnForeignParser(final IndexBuilder enIndexBuilder,
        final IndexBuilder otherIndexBuilder, final Pattern langPattern,
        final Pattern langCodePattern, final boolean swap) {
      super(enIndexBuilder, otherIndexBuilder, langPattern, langCodePattern, swap);
    }

    @Override
    void parseSection(String heading, String text) {
      if (isIgnorableTitle(title)) {
        return;
      }
      final String lang = heading.replaceAll("=", "").trim();
      if (!langPattern.matcher(lang).find()){
        return;
      }
      
      final WikiTokenizer wikiTokenizer = new WikiTokenizer(text);
      while (wikiTokenizer.nextToken() != null) {
        if (wikiTokenizer.isHeading()) {
          final String headingName = wikiTokenizer.headingWikiText();
          if (headingName.equals("Translations")) {
            LOG.warning("Translations not in English section: " + title);
            incrementCount("WARNING: Translations not in English section");
          } else if (headingName.equals("Pronunciation")) {
            //doPronunciation(wikiLineReader);
          } else if (partOfSpeechHeader.matcher(headingName).matches()) {
            doForeignPartOfSpeech(lang, headingName, wikiTokenizer.headingDepth(), wikiTokenizer);
          }
        } else {
          // It's not a heading.
          // TODO: optimization: skip to next heading.
        }
      }
    }
    
    static final class ListSection {
      final String firstPrefix;
      final String firstLine;
      final List<String> nextPrefixes = new ArrayList<String>();
      final List<String> nextLines = new ArrayList<String>();
      
      public ListSection(String firstPrefix, String firstLine) {
        this.firstPrefix = firstPrefix;
        this.firstLine = firstLine;
      }

      @Override
      public String toString() {
        return firstPrefix + firstLine + "{ " + nextPrefixes + "}";
      }
    }

    int foreignCount = 0;
    private void doForeignPartOfSpeech(final String lang, String posHeading, final int posDepth, WikiTokenizer wikiTokenizer) {
      if (++foreignCount % 1000 == 0) {
        LOG.info("***" + lang + ", " + title + ", pos=" + posHeading + ", foreignCount=" + foreignCount);
      }
      if (title.equals("6")) {
        System.out.println();
      }
      
      final StringBuilder foreignBuilder = new StringBuilder();
      final List<EnForeignParser.ListSection> listSections = new ArrayList<EnForeignParser.ListSection>();
      
      appendAndIndexWikiCallback.reset(foreignBuilder, null);
      this.state = State.ENGLISH_DEF_OF_FOREIGN;  // TODO: this is wrong, need new category....
      titleAppended = false;
      wordForms.clear();
      
      try {
      
      EnForeignParser.ListSection lastListSection = null;
      
      int currentHeadingDepth = posDepth;
      while (wikiTokenizer.nextToken() != null) {
        if (wikiTokenizer.isHeading()) {
          currentHeadingDepth = wikiTokenizer.headingDepth();
          
          if (currentHeadingDepth <= posDepth) {
            wikiTokenizer.returnToLineStart();
            return;
          }
        }  // heading
        
        if (currentHeadingDepth > posDepth) {
          // TODO: deal with other neat info sections inside POS
          continue;
        }
        
        if (wikiTokenizer.isFunction()) {
          final String name = wikiTokenizer.functionName();
          final List<String> args = wikiTokenizer.functionPositionArgs();
          final Map<String,String> namedArgs = wikiTokenizer.functionNamedArgs();
          // First line is generally a repeat of the title with some extra information.
          // We need to build up the left side (foreign text, tokens) separately from the
          // right side (English).  The left-side may get paired with multiple right sides.
          // The left side should get filed under every form of the word in question (singular, plural).
          
          // For verbs, the conjugation comes later on in a deeper section.
          // Ideally, we'd want to file every English entry with the verb
          // under every verb form coming from the conjugation.
          // Ie. under "fa": see: "make :: fare" and "do :: fare"
          // But then where should we put the conjugation table?
          // I think just under fare.  But then we need a way to link to the entry (actually the row, since entries doesn't show up!)
          // for the conjugation table from "fa".
          // Would like to be able to link to a lang#token.
          
          appendAndIndexWikiCallback.onFunction(wikiTokenizer, name, args, namedArgs);
          
        } else if (wikiTokenizer.isListItem()) {
          final String prefix = wikiTokenizer.listItemPrefix();
          if (lastListSection != null && 
              prefix.startsWith(lastListSection.firstPrefix) && 
              prefix.length() > lastListSection.firstPrefix.length()) {
            lastListSection.nextPrefixes.add(prefix);
            lastListSection.nextLines.add(wikiTokenizer.listItemWikiText());
          } else {
            lastListSection = new ListSection(prefix, wikiTokenizer.listItemWikiText());
            listSections.add(lastListSection);
          }
        } else if (lastListSection != null) {
          // Don't append anything after the lists, because there's crap.
        } else if (wikiTokenizer.isWikiLink()) {
          // Unindexed!
          foreignBuilder.append(wikiTokenizer.wikiLinkText());
          
        } else if (wikiTokenizer.isPlainText()) {
          // Unindexed!
          foreignBuilder.append(wikiTokenizer.token());
          
        } else if (wikiTokenizer.isMarkup() || wikiTokenizer.isNewline() || wikiTokenizer.isComment()) {
          // Do nothing.
        } else {
          LOG.warning("Unexpected token: " + wikiTokenizer.token());
          assert false;
        }
      }
      
      } finally {
        // Here's where we exit.
        // Should we make an entry even if there are no foreign list items?
        String foreign = foreignBuilder.toString().trim();
        if (!titleAppended && !foreign.toLowerCase().startsWith(title.toLowerCase())) {
          foreign = String.format("%s %s", title, foreign);
        }
        if (!langPattern.matcher(lang).matches()) {
          foreign = String.format("(%s) %s", lang, foreign);
        }
        for (final EnForeignParser.ListSection listSection : listSections) {
          doForeignListSection(foreign, title, wordForms, listSection);
        }
      }
    }
    
    private void doForeignListSection(final String foreignText, String title, final Collection<String> forms, final EnForeignParser.ListSection listSection) {
      state = State.ENGLISH_DEF_OF_FOREIGN;
      final String prefix = listSection.firstPrefix;
      if (prefix.length() > 1) {
        // Could just get looser and say that any prefix longer than first is a sublist.
        LOG.warning("Prefix too long: " + listSection);
        incrementCount("WARNING: Prefix too long");
        return;
      }
      
      final PairEntry pairEntry = new PairEntry(entrySource);
      final IndexedEntry indexedEntry = new IndexedEntry(pairEntry);

      entryIsFormOfSomething = false;
      final StringBuilder englishBuilder = new StringBuilder();
      final String mainLine = listSection.firstLine;
      appendAndIndexWikiCallback.reset(englishBuilder, indexedEntry);
      appendAndIndexWikiCallback.dispatch(mainLine, enIndexBuilder, EntryTypeName.WIKTIONARY_ENGLISH_DEF);

      final String english = trim(englishBuilder.toString());
      if (english.length() > 0) {
        final Pair pair = new Pair(english, trim(foreignText), this.swap);
        pairEntry.pairs.add(pair);
        foreignIndexBuilder.addEntryWithString(indexedEntry, title, entryIsFormOfSomething ? EntryTypeName.WIKTIONARY_IS_FORM_OF_SOMETHING_ELSE : EntryTypeName.WIKTIONARY_TITLE_MULTI);
        for (final String form : forms) {
          foreignIndexBuilder.addEntryWithString(indexedEntry, form, EntryTypeName.WIKTIONARY_INFLECTED_FORM_MULTI);
        }
      }
      
      // Do examples.
      String lastForeign = null;
      for (int i = 0; i < listSection.nextPrefixes.size(); ++i) {
        final String nextPrefix = listSection.nextPrefixes.get(i);
        final String nextLine = listSection.nextLines.get(i);

        // TODO: This splitting is not sensitive to wiki code.
        int dash = nextLine.indexOf("&mdash;");
        int mdashLen = 7;
        if (dash == -1) {
          dash = nextLine.indexOf("—");
          mdashLen = 1;
        }
        if (dash == -1) {
          dash = nextLine.indexOf(" - ");
          mdashLen = 3;
        }
        
        if ((nextPrefix.equals("#:") || nextPrefix.equals("##:")) && dash != -1) {
          final String foreignEx = nextLine.substring(0, dash);
          final String englishEx = nextLine.substring(dash + mdashLen);
          final Pair pair = new Pair(formatAndIndexExampleString(englishEx, enIndexBuilder, indexedEntry), formatAndIndexExampleString(foreignEx, foreignIndexBuilder, indexedEntry), swap);
          if (pair.lang1 != "--" && pair.lang1 != "--") {
            pairEntry.pairs.add(pair);
          }
          lastForeign = null;
        } else if (nextPrefix.equals("#:") || nextPrefix.equals("##:")){
          final Pair pair = new Pair("--", formatAndIndexExampleString(nextLine, null, indexedEntry), swap);
          lastForeign = nextLine;
          if (pair.lang1 != "--" && pair.lang1 != "--") {
            pairEntry.pairs.add(pair);
          }
        } else if (nextPrefix.equals("#::") || nextPrefix.equals("#**")) {
          if (lastForeign != null && pairEntry.pairs.size() > 0) {
            pairEntry.pairs.remove(pairEntry.pairs.size() - 1);
            final Pair pair = new Pair(formatAndIndexExampleString(nextLine, enIndexBuilder, indexedEntry), formatAndIndexExampleString(lastForeign, foreignIndexBuilder, indexedEntry), swap);
            if (pair.lang1 != "--" || pair.lang2 != "--") {
              pairEntry.pairs.add(pair);
            }
            lastForeign = null;
          } else {
            LOG.warning("TODO: English example with no foreign: " + title + ", " + nextLine);
            final Pair pair = new Pair("--", formatAndIndexExampleString(nextLine, null, indexedEntry), swap);
            if (pair.lang1 != "--" || pair.lang2 != "--") {
              pairEntry.pairs.add(pair);
            }
          }
        } else if (nextPrefix.equals("#*")) {
          // Can't really index these.
          final Pair pair = new Pair("--", formatAndIndexExampleString(nextLine, null, indexedEntry), swap);
          lastForeign = nextLine;
          if (pair.lang1 != "--" || pair.lang2 != "--") {
            pairEntry.pairs.add(pair);
          }
        } else if (nextPrefix.equals("#::*") || nextPrefix.equals("##") || nextPrefix.equals("#*:") || nextPrefix.equals("#:*") || true) {
          final Pair pair = new Pair("--", formatAndIndexExampleString(nextLine, null, indexedEntry), swap);
          if (pair.lang1 != "--" || pair.lang2 != "--") {
            pairEntry.pairs.add(pair);
          }
//        } else {
//          assert false;
        }
      }
    }
    
    private String formatAndIndexExampleString(final String example, final IndexBuilder indexBuilder, final IndexedEntry indexedEntry) {
      // TODO:
//      if (wikiTokenizer.token().equals("'''")) {
//        insideTripleQuotes = !insideTripleQuotes;
//      }
      final StringBuilder builder = new StringBuilder();
      appendAndIndexWikiCallback.reset(builder, indexedEntry);
      appendAndIndexWikiCallback.entryTypeName = EntryTypeName.WIKTIONARY_EXAMPLE;
      appendAndIndexWikiCallback.entryTypeNameSticks = true;
      try {
        // TODO: this is a hack needed because we don't safely split on the dash.
        appendAndIndexWikiCallback.dispatch(example, indexBuilder, EntryTypeName.WIKTIONARY_EXAMPLE);
      } catch (AssertionError e) {
        return "--";
      }
      final String result = trim(builder.toString());
      return result.length() > 0 ? result : "--";
    }


    private void itConjAre(List<String> args, Map<String, String> namedArgs) {
      final String base = args.get(0);
      final String aux = args.get(1);
      
      putIfMissing(namedArgs, "inf", base + "are");
      putIfMissing(namedArgs, "aux", aux);
      putIfMissing(namedArgs, "ger", base + "ando");
      putIfMissing(namedArgs, "presp", base + "ante");
      putIfMissing(namedArgs, "pastp", base + "ato");
      // Present
      putIfMissing(namedArgs, "pres1s", base + "o");
      putIfMissing(namedArgs, "pres2s", base + "i");
      putIfMissing(namedArgs, "pres3s", base + "a");
      putIfMissing(namedArgs, "pres1p", base + "iamo");
      putIfMissing(namedArgs, "pres2p", base + "ate");
      putIfMissing(namedArgs, "pres3p", base + "ano");
      // Imperfect
      putIfMissing(namedArgs, "imperf1s", base + "avo");
      putIfMissing(namedArgs, "imperf2s", base + "avi");
      putIfMissing(namedArgs, "imperf3s", base + "ava");
      putIfMissing(namedArgs, "imperf1p", base + "avamo");
      putIfMissing(namedArgs, "imperf2p", base + "avate");
      putIfMissing(namedArgs, "imperf3p", base + "avano");
      // Passato remoto
      putIfMissing(namedArgs, "prem1s", base + "ai");
      putIfMissing(namedArgs, "prem2s", base + "asti");
      putIfMissing(namedArgs, "prem3s", base + "ò");
      putIfMissing(namedArgs, "prem1p", base + "ammo");
      putIfMissing(namedArgs, "prem2p", base + "aste");
      putIfMissing(namedArgs, "prem3p", base + "arono");
      // Future
      putIfMissing(namedArgs, "fut1s", base + "erò");
      putIfMissing(namedArgs, "fut2s", base + "erai");
      putIfMissing(namedArgs, "fut3s", base + "erà");
      putIfMissing(namedArgs, "fut1p", base + "eremo");
      putIfMissing(namedArgs, "fut2p", base + "erete");
      putIfMissing(namedArgs, "fut3p", base + "eranno");
      // Conditional
      putIfMissing(namedArgs, "cond1s", base + "erei");
      putIfMissing(namedArgs, "cond2s", base + "eresti");
      putIfMissing(namedArgs, "cond3s", base + "erebbe");
      putIfMissing(namedArgs, "cond1p", base + "eremmo");
      putIfMissing(namedArgs, "cond2p", base + "ereste");
      putIfMissing(namedArgs, "cond3p", base + "erebbero");
      // Subjunctive / congiuntivo
      putIfMissing(namedArgs, "sub123s", base + "i");
      putIfMissing(namedArgs, "sub1p", base + "iamo");
      putIfMissing(namedArgs, "sub2p", base + "iate");
      putIfMissing(namedArgs, "sub3p", base + "ino");
      // Imperfect subjunctive
      putIfMissing(namedArgs, "impsub12s", base + "assi");
      putIfMissing(namedArgs, "impsub3s", base + "asse");
      putIfMissing(namedArgs, "impsub1p", base + "assimo");
      putIfMissing(namedArgs, "impsub2p", base + "aste");
      putIfMissing(namedArgs, "impsub3p", base + "assero");
      // Imperative
      putIfMissing(namedArgs, "imp2s", base + "a");
      putIfMissing(namedArgs, "imp3s", base + "i");
      putIfMissing(namedArgs, "imp1p", base + "iamo");
      putIfMissing(namedArgs, "imp2p", base + "ate");
      putIfMissing(namedArgs, "imp3p", base + "ino");


      itConj(args, namedArgs);
    }


    private void itConj(List<String> args, Map<String, String> namedArgs) {
      // TODO Auto-generated method stub
      
    }


    private static void putIfMissing(final Map<String, String> namedArgs, final String key,
        final String value) {
      final String oldValue = namedArgs.get(key);
      if (oldValue == null || oldValue.length() == 0) {
        namedArgs.put(key, value);
      }
    }
    
    // TODO: check how ='' and =| are manifested....
    // TODO: get this right in -are
    private static void putOrNullify(final Map<String, String> namedArgs, final String key,
        final String value) {
      final String oldValue = namedArgs.get(key);
      if (oldValue == null/* || oldValue.length() == 0*/) {
        namedArgs.put(key, value);
      } else {
        if (oldValue.equals("''")) {
          namedArgs.put(key, "");
        }
      }
    }

  }  // ForeignParser