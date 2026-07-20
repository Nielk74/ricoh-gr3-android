import Foundation
import ImageIO
import Vision

let paths = Array(CommandLine.arguments.dropFirst())

for (index, path) in paths.enumerated() {
    let url = URL(fileURLWithPath: path)
    guard
        let source = CGImageSourceCreateWithURL(url as CFURL, nil),
        let image = CGImageSourceCreateImageAtIndex(source, 0, nil)
    else {
        print("\(index)\t")
        continue
    }

    let request = VNDetectFaceRectanglesRequest()
    do {
        let handler = VNImageRequestHandler(cgImage: image, orientation: .up, options: [:])
        try handler.perform([request])
        let faces = (request.results ?? []).sorted {
            $0.boundingBox.minX < $1.boundingBox.minX
        }
        let encoded = faces.map { face -> String in
            let box = face.boundingBox
            let left = box.minX
            let top = 1.0 - box.maxY
            let right = box.maxX
            let bottom = 1.0 - box.minY
            return "\(left),\(top),\(right),\(bottom),\(face.confidence)"
        }.joined(separator: ";")
        print("\(index)\t\(encoded)")
    } catch {
        fputs("Vision face detection failed for \(path): \(error)\n", stderr)
        print("\(index)\t")
    }
}
