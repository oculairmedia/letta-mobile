package com.letta.mobile.ui.canvas

/**
 * Embedded official DrawBox sample diagrams for P0 import and export verification.
 * Source: akshay2211/DrawBox (samples/DrawBox-build-cycle.json, samples/DrawBox-daily-loop.json)
 */
object CanvasSamples {
    /** Build cycle sample diagram: Idea -> Plan -> Build -> Ship */
    val buildCycleJson: String = """{
    "bgColor": "#eceff1ff",
    "elements": [
        {
            "id": "title",
            "type": "Text",
            "zIndex": 10,
            "points": [],
            "strokeColor": "#1b2a41ff",
            "strokeWidth": 0.0,
            "modifiedAt": 1782669085173,
            "text": "Build Cycle",
            "fontFamilyKey": "serif",
            "fontSize": 64.0,
            "alignment": "CENTER",
            "textTopLeft": "502.0,212.0",
            "wrapWidth": 900.0
        },
        {
            "id": "subtitle",
            "type": "Text",
            "zIndex": 10,
            "points": [],
            "strokeColor": "#5d6d7eff",
            "strokeWidth": 0.0,
            "modifiedAt": 1782669082446,
            "text": "an idea on monday is a shipped feature by friday",
            "fontFamilyKey": "sans",
            "fontSize": 22.0,
            "alignment": "CENTER",
            "textTopLeft": "366.0,408.0",
            "wrapWidth": 1274.0
        },
        {
            "id": "track",
            "type": "Shape",
            "zIndex": 0,
            "points": [
                "268.0,650.0",
                "1528.0,650.0"
            ],
            "strokeColor": "#d5dbdbff",
            "strokeWidth": 2.0,
            "shapeType": "LINE",
            "strokeStyle": "DOTTED",
            "modifiedAt": 1782669074313
        },
        {
            "id": "idea-tri",
            "type": "Shape",
            "zIndex": 1,
            "points": [
                "210.0,542.0",
                "490.0,762.0"
            ],
            "strokeColor": "#f39c12ff",
            "strokeWidth": 5.0,
            "shapeType": "TRIANGLE",
            "fillColor": "#fce9c4ff",
            "cornerRadius": 12.0,
            "modifiedAt": 1782669102603
        },
        {
            "id": "idea-label",
            "type": "Text",
            "zIndex": 5,
            "points": [],
            "strokeColor": "#1b2a41ff",
            "strokeWidth": 0.0,
            "modifiedAt": 1782669105793,
            "text": "Idea",
            "fontFamilyKey": "sans",
            "fontSize": 30.0,
            "alignment": "CENTER",
            "textTopLeft": "294.0,643.0",
            "wrapWidth": 280.0
        },
        {
            "id": "arrow-1",
            "type": "Shape",
            "zIndex": 2,
            "points": [
                "420.0,652.0",
                "568.0,650.0"
            ],
            "strokeColor": "#34495eff",
            "strokeWidth": 3.0,
            "shapeType": "ARROW",
            "startBinding": "idea-tri",
            "endBinding": "plan-rect",
            "modifiedAt": 1782669102603
        },
        {
            "id": "plan-rect",
            "type": "Shape",
            "zIndex": 1,
            "points": [
                "568.0,560.0",
                "868.0,740.0"
            ],
            "strokeColor": "#16a085ff",
            "strokeWidth": 5.0,
            "shapeType": "RECTANGLE",
            "fillColor": "#d1f2ebff",
            "cornerRadius": 24.0,
            "modifiedAt": 1782669074313
        },
        {
            "id": "plan-label",
            "type": "Text",
            "zIndex": 5,
            "points": [],
            "strokeColor": "#0e6655ff",
            "strokeWidth": 0.0,
            "modifiedAt": 1782669108292,
            "text": "Plan",
            "fontFamilyKey": "sans",
            "fontSize": 36.0,
            "alignment": "CENTER",
            "textTopLeft": "642.0,600.0",
            "wrapWidth": 300.0
        },
        {
            "id": "arrow-2",
            "type": "Shape",
            "zIndex": 2,
            "points": [
                "868.0,650.0",
                "948.0,650.0"
            ],
            "strokeColor": "#34495eff",
            "strokeWidth": 3.0,
            "shapeType": "ARROW",
            "startBinding": "plan-rect",
            "endBinding": "build-rect",
            "modifiedAt": 1782669074313
        },
        {
            "id": "build-rect",
            "type": "Shape",
            "zIndex": 1,
            "points": [
                "948.0,560.0",
                "1248.0,740.0"
            ],
            "strokeColor": "#e74c3cff",
            "strokeWidth": 5.0,
            "shapeType": "RECTANGLE",
            "fillColor": "#fadbd8ff",
            "cornerRadius": 24.0,
            "strokeStyle": "DASHED",
            "modifiedAt": 1782669074313
        },
        {
            "id": "build-label",
            "type": "Text",
            "zIndex": 5,
            "points": [],
            "strokeColor": "#922b21ff",
            "strokeWidth": 0.0,
            "modifiedAt": 1782669111523,
            "text": "Build",
            "fontFamilyKey": "mono",
            "fontSize": 36.0,
            "alignment": "CENTER",
            "textTopLeft": "988.0,602.0",
            "wrapWidth": 300.0
        },
        {
            "id": "arrow-3",
            "type": "Shape",
            "zIndex": 2,
            "points": [
                "1248.0,650.0",
                "1328.0,650.0"
            ],
            "strokeColor": "#34495eff",
            "strokeWidth": 3.0,
            "shapeType": "ARROW",
            "startBinding": "build-rect",
            "endBinding": "ship-circle",
            "modifiedAt": 1782669074313
        },
        {
            "id": "ship-circle",
            "type": "Shape",
            "zIndex": 1,
            "points": [
                "1328.0,650.0",
                "1528.0,650.0"
            ],
            "strokeColor": "#27ae60ff",
            "strokeWidth": 0.0,
            "shapeType": "CIRCLE",
            "fillColor": "#27ae60ff",
            "modifiedAt": 1782669074313,
            "strokeEnabled": false
        },
        {
            "id": "ship-label",
            "type": "Text",
            "zIndex": 5,
            "points": [],
            "strokeColor": "#ffffffff",
            "strokeWidth": 0.0,
            "modifiedAt": 1782669113638,
            "text": "Ship",
            "fontFamilyKey": "sans",
            "fontSize": 32.0,
            "alignment": "CENTER",
            "textTopLeft": "1360.0,607.0",
            "wrapWidth": 200.0
        },
        {
            "id": "caption",
            "type": "Text",
            "zIndex": 10,
            "points": [],
            "strokeColor": "#7f8c8dff",
            "strokeWidth": 0.0,
            "modifiedAt": 1782669122928,
            "text": "from sketch to release · four moves · one repo",
            "fontFamilyKey": "serif",
            "fontSize": 20.0,
            "alignment": "CENTER",
            "textTopLeft": "428.0,822.0",
            "wrapWidth": 900.0
        }
    ]
}
"""

    /** Daily loop sample diagram */
    val dailyLoopJson: String = """{
    "bgColor": "#fff8f0ff",
    "elements": [
        {
            "id": "title",
            "type": "Text",
            "zIndex": 10,
            "points": [],
            "strokeColor": "#2c3e50ff",
            "strokeWidth": 0.0,
            "modifiedAt": 1782669322976,
            "text": "Daily Loop",
            "fontFamilyKey": "serif",
            "fontSize": 56.0,
            "alignment": "CENTER",
            "textTopLeft": "108.0,34.0",
            "wrapWidth": 600.0
        },
        {
            "id": "subtitle",
            "type": "Text",
            "zIndex": 10,
            "points": [],
            "strokeColor": "#7f8c8dff",
            "strokeWidth": 0.0,
            "modifiedAt": 1782669330731,
            "text": "eat · drink coffee · sleep · repeat",
            "fontFamilyKey": "sans",
            "fontSize": 22.0,
            "alignment": "CENTER",
            "textTopLeft": "78.0,160.0",
            "wrapWidth": 692.0
        },
        {
            "id": "divider",
            "type": "Shape",
            "zIndex": 1,
            "points": [
                "280.0,200.0",
                "520.0,200.0"
            ],
            "strokeColor": "#bdc3c7ff",
            "strokeWidth": 2.0,
            "shapeType": "LINE"
        },
        {
            "id": "eat-box",
            "type": "Shape",
            "zIndex": 1,
            "points": [
                "150.0,240.0",
                "650.0,360.0"
            ],
            "strokeColor": "#e67e22ff",
            "strokeWidth": 5.0,
            "shapeType": "RECTANGLE",
            "fillColor": "#ffeaa7ff",
            "cornerRadius": 30.0
        },
        {
            "id": "eat-label",
            "type": "Text",
            "zIndex": 5,
            "points": [],
            "strokeColor": "#2c3e50ff",
            "strokeWidth": 0.0,
            "modifiedAt": 1782669303327,
            "text": "Eat",
            "fontFamilyKey": "mono",
            "fontSize": 42.0,
            "alignment": "CENTER",
            "textTopLeft": "324.0,251.0",
            "wrapWidth": 162.0
        },
        {
            "id": "arrow-eat-coffee",
            "type": "Shape",
            "zIndex": 2,
            "points": [
                "400.0,360.0",
                "400.0,430.29437"
            ],
            "strokeColor": "#34495eff",
            "strokeWidth": 4.0,
            "shapeType": "ARROW",
            "startBinding": "eat-box",
            "endBinding": "coffee-circle",
            "modifiedAt": 1782669280844
        },
        {
            "id": "coffee-circle",
            "type": "Shape",
            "zIndex": 1,
            "points": [
                "280.0,480.0",
                "520.0,720.0"
            ],
            "strokeColor": "#6f4e37ff",
            "strokeWidth": 0.0,
            "shapeType": "CIRCLE",
            "fillColor": "#a0522dff",
            "strokeEnabled": false
        },
        {
            "id": "coffee-label",
            "type": "Text",
            "zIndex": 5,
            "points": [],
            "strokeColor": "#ffffffff",
            "strokeWidth": 0.0,
            "modifiedAt": 1782669296172,
            "text": "Drink\nCoffee",
            "fontFamilyKey": "serif",
            "fontSize": 32.0,
            "alignment": "CENTER",
            "textTopLeft": "302.0,516.0",
            "wrapWidth": 240.0
        },
        {
            "id": "arrow-coffee-sleep",
            "type": "Shape",
            "zIndex": 2,
            "points": [
                "400.0,769.7056",
                "400.0,840.0"
            ],
            "strokeColor": "#34495eff",
            "strokeWidth": 4.0,
            "shapeType": "ARROW",
            "startBinding": "coffee-circle",
            "endBinding": "sleep-box",
            "modifiedAt": 1782669280844
        },
        {
            "id": "sleep-box",
            "type": "Shape",
            "zIndex": 1,
            "points": [
                "150.0,840.0",
                "650.0,960.0"
            ],
            "strokeColor": "#3498dbff",
            "strokeWidth": 5.0,
            "shapeType": "RECTANGLE",
            "fillColor": "#d6eaf8ff",
            "cornerRadius": 30.0,
            "strokeStyle": "DASHED"
        },
        {
            "id": "sleep-label",
            "type": "Text",
            "zIndex": 5,
            "points": [],
            "strokeColor": "#2c3e50ff",
            "strokeWidth": 0.0,
            "modifiedAt": 1782669310972,
            "text": "Sleep",
            "fontFamilyKey": "mono",
            "fontSize": 42.0,
            "alignment": "CENTER",
            "textTopLeft": "270.0,847.0",
            "wrapWidth": 270.0
        },
        {
            "id": "arrow-sleep-repeat",
            "type": "Shape",
            "zIndex": 2,
            "points": [
                "400.0,960.0",
                "400.0,1080.0"
            ],
            "strokeColor": "#34495eff",
            "strokeWidth": 4.0,
            "shapeType": "ARROW",
            "startBinding": "sleep-box",
            "endBinding": "repeat-box"
        },
        {
            "id": "repeat-box",
            "type": "Shape",
            "zIndex": 1,
            "points": [
                "150.0,1080.0",
                "650.0,1200.0"
            ],
            "strokeColor": "#9b59b6ff",
            "strokeWidth": 5.0,
            "shapeType": "RECTANGLE",
            "fillColor": "#f4ecf7ff",
            "cornerRadius": 30.0
        },
        {
            "id": "repeat-label",
            "type": "Text",
            "zIndex": 5,
            "points": [],
            "strokeColor": "#2c3e50ff",
            "strokeWidth": 0.0,
            "modifiedAt": 1782669318743,
            "text": "Repeat",
            "fontFamilyKey": "sans",
            "fontSize": 42.0,
            "alignment": "CENTER",
            "textTopLeft": "250.0,1085.0",
            "wrapWidth": 300.0
        },
        {
            "id": "86f85c95-0098-4258-8f45-5fd9ef0dd10e",
            "type": "Path",
            "zIndex": 15,
            "points": [],
            "strokeColor": "#ff0000ff",
            "strokeWidth": 10.0,
            "alpha": 1.0,
            "createdAt": 1782669276307,
            "modifiedAt": 1782669276307,
            "samples": [
                "768.0,846.0,10.0"
            ]
        }
    ]
}
"""
}
