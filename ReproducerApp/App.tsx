/**
 * Android: a ScrollView with `maintainVisibleContentPosition` that is never
 * attached to a window stays registered as a FabricUIManager listener.
 *
 * The ScrollView below sits outside the bounds of a parent with
 * `removeClippedSubviews`, so ReactViewGroup never adds it to the view
 * hierarchy. It is created and gets its props, but it is never attached.
 *
 * Steps are in the README.
 */

import React, { useState } from 'react';
import { Button, ScrollView, StyleSheet, Text, View } from 'react-native';

// Module scope, so the choice survives an Activity recreation: the JS runtime
// is kept, and the new surface renders App again from scratch.
let withPropAtStart = true;

export default function App() {
  const [withProp, setWithProp] = useState(withPropAtStart);
  const [remounts, setRemounts] = useState(0);

  const toggleProp = () => {
    withPropAtStart = !withProp;
    setWithProp(!withProp);
  };

  return (
    <View style={styles.root}>
      <Text style={styles.title}>maintainVisibleContentPosition leak</Text>
      <Text testID="mode" style={styles.mode}>
        {withProp
          ? 'Hidden ScrollView WITH maintainVisibleContentPosition'
          : 'Hidden ScrollView WITHOUT the prop (control)'}
      </Text>
      <Button
        testID="toggle"
        title={withProp ? 'Switch to control (no prop)' : 'Switch to prop'}
        onPress={toggleProp}
      />
      <View style={styles.gap} />
      <Button
        testID="remount"
        title={`Remount hidden ScrollView (${remounts})`}
        onPress={() => setRemounts(n => n + 1)}
      />

      {/* 40 dp tall and clips its children. The ScrollView is laid out
          below it, so it is never added to the view hierarchy. */}
      <View removeClippedSubviews style={styles.clipper}>
        <ScrollView
          key={remounts}
          style={styles.hidden}
          maintainVisibleContentPosition={
            withProp ? { minIndexForVisible: 0 } : undefined
          }
        >
          <Text>Never attached to a window</Text>
        </ScrollView>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, padding: 24, paddingTop: 64, backgroundColor: 'white' },
  title: { fontSize: 20, fontWeight: 'bold', color: 'black' },
  mode: { marginVertical: 16, fontSize: 16, color: 'black' },
  gap: { height: 12 },
  clipper: { height: 40, marginTop: 24 },
  hidden: { position: 'absolute', top: 100, height: 100, width: 100 },
});
