import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:obdbridge_example/main.dart';

void main() {
  testWidgets('shows the obdbridge version', (WidgetTester tester) async {
    await tester.pumpWidget(const MyApp());

    expect(
      find.byWidgetPredicate(
        (Widget widget) =>
            widget is Text && widget.data!.startsWith('obdbridge version:'),
      ),
      findsOneWidget,
    );
  });
}
