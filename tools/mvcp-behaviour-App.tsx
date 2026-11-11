// Position check for the possible fixes (see the README). Not part of the reproducer: copy it over
// ReproducerApp/App.tsx to use it. It checks that maintainVisibleContentPosition still keeps the
// first visible row in place when rows are prepended, for a ScrollView that is attached at mount
// (left) and one that is attached later, when "Reveal" moves it into its clipping parent (right).
import React, { useState } from 'react';
import { Button, ScrollView, Text, View } from 'react-native';

const range = (a: number, b: number) =>
  Array.from({ length: b - a }, (_, i) => a + i);

function List({ prefix, items }: { prefix: string; items: number[] }) {
  return items.map(n => (
    <Text key={n} style={{ height: 50, fontSize: 16, color: 'black' }}>
      {prefix} Row {n}
    </Text>
  ));
}

export default function App() {
  const [items, setItems] = useState(() => range(100, 140));
  const [revealed, setRevealed] = useState(false);
  const mvcp = { minIndexForVisible: 0 };
  return (
    <View style={{ flex: 1, paddingTop: 48, backgroundColor: 'white' }}>
      <Button
        title="Prepend 5"
        onPress={() => setItems(c => [...range(c[0] - 5, c[0]), ...c])}
      />
      <Button title="Reveal" onPress={() => setRevealed(true)} />
      <View style={{ flexDirection: 'row', height: 500, marginTop: 12 }}>
        <ScrollView style={{ flex: 1 }} maintainVisibleContentPosition={mvcp}>
          <List prefix="L" items={items} />
        </ScrollView>
        <View removeClippedSubviews style={{ flex: 1 }}>
          <ScrollView
            style={{
              position: 'absolute',
              left: 0,
              right: 0,
              height: 500,
              top: revealed ? 0 : 600,
            }}
            maintainVisibleContentPosition={mvcp}
          >
            <List prefix="R" items={items} />
          </ScrollView>
        </View>
      </View>
    </View>
  );
}
