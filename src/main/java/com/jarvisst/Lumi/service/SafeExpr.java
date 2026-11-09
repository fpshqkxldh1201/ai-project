package com.jarvisst.Lumi.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleUnaryOperator;

/**
 * 그래프용 수식 계산기. 모델이 알려 준 식("x^2", "sin(x)/x" …)을 서버가 직접 계산해 점을 만든다.
 * 모델이 점 값을 지어내면 틀릴 수 있어서, 식만 받고 계산은 여기서 한다.
 *
 * 허용: 숫자, x, pi, e, + - * / ^, 괄호, 2x·3(x+1) 같은 곱셈 생략,
 *       sin cos tan asin acos atan sinh cosh tanh exp ln log sqrt abs.
 * 길이·깊이를 제한하고 그 밖의 글자는 모두 거부한다(코드 실행이나 외부 호출 경로가 없다).
 */
public final class SafeExpr {

    private SafeExpr() {
    }

    private static final int MAX_LEN = 80;
    private static final int MAX_DEPTH = 12;

    /** 식을 x에 대한 함수로 만든다. 문법 오류면 IllegalArgumentException. */
    public static DoubleUnaryOperator compile(String text) {
        if (text == null) {
            throw new IllegalArgumentException("식이 없습니다.");
        }
        String s = text.strip().replace("−", "-").replace("×", "*").replace("·", "*").replace("÷", "/")
                .replace("²", "^2").replace("³", "^3").replace("π", "pi");
        s = s.replaceAll("^(f|y|g)\\s*(\\(\\s*x\\s*\\))?\\s*=\\s*", "");
        if (s.isEmpty() || s.length() > MAX_LEN) {
            throw new IllegalArgumentException("식이 비었거나 너무 깁니다.");
        }
        Parser p = new Parser(s.toLowerCase(Locale.ROOT));
        Node n = p.parseAll();
        return n::eval;
    }

    private interface Node {
        double eval(double x);
    }

    private static final class Parser {
        private final String s;
        private int i = 0;
        private int depth = 0;

        Parser(String s) {
            this.s = s;
        }

        Node parseAll() {
            Node n = expr();
            skip();
            if (i != s.length()) {
                throw new IllegalArgumentException("해석할 수 없는 글자: " + s.charAt(i));
            }
            return n;
        }

        private void skip() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        private boolean eat(char c) {
            skip();
            if (i < s.length() && s.charAt(i) == c) {
                i++;
                return true;
            }
            return false;
        }

        private Node expr() {
            Node left = term();
            while (true) {
                if (eat('+')) {
                    Node a = left, b = term();
                    left = x -> a.eval(x) + b.eval(x);
                } else if (eat('-')) {
                    Node a = left, b = term();
                    left = x -> a.eval(x) - b.eval(x);
                } else {
                    return left;
                }
            }
        }

        private Node term() {
            Node left = unary();
            while (true) {
                if (eat('*')) {
                    Node a = left, b = unary();
                    left = x -> a.eval(x) * b.eval(x);
                } else if (eat('/')) {
                    Node a = left, b = unary();
                    left = x -> a.eval(x) / b.eval(x);
                } else if (startsOperand()) { // 2x, 3(x+1), x sin(x)
                    Node a = left, b = unary();
                    left = x -> a.eval(x) * b.eval(x);
                } else {
                    return left;
                }
            }
        }

        private boolean startsOperand() {
            skip();
            if (i >= s.length()) {
                return false;
            }
            char c = s.charAt(i);
            return c == '(' || Character.isLetter(c) || Character.isDigit(c) || c == '.';
        }

        private Node unary() {
            if (eat('-')) {
                Node a = unary();
                return x -> -a.eval(x);
            }
            if (eat('+')) {
                return unary();
            }
            return power();
        }

        private Node power() {
            Node base = atom();
            skip();
            if (i < s.length() && s.charAt(i) == '^') {
                i++;
                Node exp = unary(); // 오른쪽 결합: 2^-1, 2^3^2
                Node b = base;
                return x -> Math.pow(b.eval(x), exp.eval(x));
            }
            return base;
        }

        private Node atom() {
            skip();
            if (i >= s.length()) {
                throw new IllegalArgumentException("식이 중간에 끝났습니다.");
            }
            if (++depth > MAX_DEPTH) {
                throw new IllegalArgumentException("괄호가 너무 깊습니다.");
            }
            try {
                char c = s.charAt(i);
                if (c == '(') {
                    i++;
                    Node n = expr();
                    if (!eat(')')) {
                        throw new IllegalArgumentException("닫는 괄호가 없습니다.");
                    }
                    return n;
                }
                if (Character.isDigit(c) || c == '.') {
                    int st = i;
                    while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
                        i++;
                    }
                    final double v;
                    try {
                        v = Double.parseDouble(s.substring(st, i));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("숫자 형식이 잘못됐습니다.");
                    }
                    return x -> v;
                }
                if (Character.isLetter(c)) {
                    int st = i;
                    while (i < s.length() && Character.isLetter(s.charAt(i))) {
                        i++;
                    }
                    String name = s.substring(st, i);
                    switch (name) {
                        case "x":
                            return x -> x;
                        case "pi":
                            return x -> Math.PI;
                        case "e":
                            return x -> Math.E;
                        default:
                            break;
                    }
                    DoubleUnaryOperator f = function(name);
                    if (f == null) {
                        // "xy" 같은 붙여 쓴 글자는 허용하지 않는다(변수는 x 하나뿐).
                        throw new IllegalArgumentException("모르는 이름: " + name);
                    }
                    if (!eat('(')) {
                        throw new IllegalArgumentException(name + " 뒤에는 괄호가 필요합니다.");
                    }
                    Node arg = expr();
                    if (!eat(')')) {
                        throw new IllegalArgumentException("닫는 괄호가 없습니다.");
                    }
                    return x -> f.applyAsDouble(arg.eval(x));
                }
                throw new IllegalArgumentException("해석할 수 없는 글자: " + c);
            } finally {
                depth--;
            }
        }

        private static DoubleUnaryOperator function(String name) {
            return switch (name) {
                case "sin" -> Math::sin;
                case "cos" -> Math::cos;
                case "tan" -> Math::tan;
                case "asin" -> Math::asin;
                case "acos" -> Math::acos;
                case "atan" -> Math::atan;
                case "sinh" -> Math::sinh;
                case "cosh" -> Math::cosh;
                case "tanh" -> Math::tanh;
                case "exp" -> Math::exp;
                case "ln" -> Math::log;
                case "log" -> Math::log10;
                case "sqrt" -> Math::sqrt;
                case "abs" -> Math::abs;
                default -> null;
            };
        }
    }

    /** [xmin, xmax]를 n등분해 (x, y) 점을 만든다. 계산이 안 되거나 너무 큰 점은 빼서 그래프가 끊기게 한다. */
    public static List<double[]> sample(DoubleUnaryOperator f, double xmin, double xmax, int n) {
        List<double[]> pts = new ArrayList<>();
        for (int k = 0; k <= n; k++) {
            double x = xmin + (xmax - xmin) * k / n;
            double y = f.applyAsDouble(x);
            if (Double.isFinite(y) && Math.abs(y) <= 1e6) {
                pts.add(new double[]{round(x), round(y)});
            }
        }
        return pts;
    }

    /** 중앙 차분으로 구한 도함수 f'(x). 개념 설명에서 f와 f'를 함께 그릴 때 쓴다. */
    public static DoubleUnaryOperator derivative(DoubleUnaryOperator f, double h) {
        return x -> (f.applyAsDouble(x + h) - f.applyAsDouble(x - h)) / (2 * h);
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
