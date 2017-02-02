package ca.waterloo.dsg.graphflow.util;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Represents the possible data types of property values.
 */
public enum DataType {
    INT, DOUBLE, BOOLEAN, STRING;

    /**
     * Converts the {@code String} dataType to the actual {@link DataType} enum.
     *
     * @param stringDataType The {@code String} dataType.
     * @return The {@link DataType} enum obtained from the {@code String} stringDataType.
     * @throws IllegalArgumentException if {@code stringDataType} is not one of the {@link DataType}
     * enum values.
     */
    public static DataType mapStringToDataType(String stringDataType) {
        stringDataType = stringDataType.toUpperCase();
        if (INT.name().matches(stringDataType)) {
            return INT;
        }  else if (DOUBLE.name().matches(stringDataType)) {
            return DOUBLE;
        } else if (BOOLEAN.name().matches(stringDataType)) {
            return BOOLEAN;
        } else if (STRING.name().matches(stringDataType)) {
            return STRING;
        }

        throw new IllegalArgumentException("The data type " + stringDataType + " is not " +
            "supported.");
    }

    /**
     * Checks if the {@code String} value can be cast to its actual data type.
     *
     * @param stringDataType The {@code String} dataType.
     * @param stringValue The {@code String} value.
     * @throws IllegalArgumentException if the  {@code stringDataType} is not one of the {@link
     * DataType} enum values or if the casting cannot be done.
     */
    public static void assertValueCanBeCastToDataType(String stringDataType, String stringValue) {
        DataType dataType = mapStringToDataType(stringDataType);
        if (BOOLEAN == dataType && !stringValue.toUpperCase().equals("TRUE") &&
            !stringValue.toUpperCase().equals("FALSE")) {
            throw new IllegalArgumentException("The string value " + stringValue + " can not be " +
                "parsed as BOOLEAN. It has to be equal to true or false ignoring case.");
        }
        try {
            if (INT == dataType) {
                Integer.parseInt(stringValue);
            } else if (DOUBLE == dataType) {
                Double.parseDouble(stringValue);
            }
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException("The string value " + stringValue + " can not be " +
                "parsed as " + stringDataType.toUpperCase() + ".");
        }
    }

    /**
     * @param dataType The {@link DataType} of the passed values.
     * @param thisValue First value as {@code Object}.
     * @param thatValue Second value as {@code String}.
     * @throws IllegalArgumentException if the dataType is not one of the {@link DataType} enum
     * values or if casting of {@code thatValue} or {@code thisValue} to {@link DataType} fails.
     */
    public static boolean equals(DataType dataType, Object thisValue, String thatValue) {
        if (null == thatValue || null == thisValue) {
            return thisValue == thatValue;
        }

        try {
            if (INT == dataType) {
                return (int) thisValue == Integer.parseInt(thatValue);
            } else if (DOUBLE == dataType) {
                return (double) thisValue == Double.parseDouble(thatValue);
            } else if (BOOLEAN == dataType) {
                return (boolean) thisValue == Boolean.parseBoolean(thatValue);
            } else if (STRING == dataType) {
                return thatValue.equals(thisValue);
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Either one of the string values can not be cast" +
                " to " + dataType.name() + ".");
        }

        throw new IllegalArgumentException("The data type " + dataType + " is null or not " +
            "supported.");
    }

    /**
     * @param dataType One of the {@link DataType} enum values.
     * @return The number of bytes required to store the {@code dataType}.
     * @throws IllegalArgumentException if {@code dataType} passed is not one of the {@link
     * DataType} enum values.
     */
    public static int getLength(DataType dataType) {
        if (BOOLEAN == dataType) {
            return 1;
        } else if (INT == dataType) {
            return 4;
        } else if (DOUBLE == dataType) {
            return 8;
        } else if (STRING == dataType) {
            throw new IllegalArgumentException("The number of bytes for String is not fixed.");
        }

        throw new IllegalArgumentException("The data type " + dataType + " is null or not " +
            "supported.");
    }

    /**
     * Serializes the {@code short} key, and {@code String} value passed to a byte array based on
     * the {@code dataType} passed.
     *
     * @param key The key of the key-value pair.
     * @param dataType The {@code DataType} of the value.
     * @param value The value of the key-value pair.
     * @return The {@code byte[]} containing the serialized bytes.
     * @throws IllegalArgumentException if the dataType passed is not one of the {@link DataType}
     * enum values, if {@code value} is {@code null} or if the value cannot be cast to the given
     * {@code dataType}.
     */
    public static byte[] serialize(DataType dataType, short key, String value) {
        if (null == value) {
            throw new IllegalArgumentException("String value passed is null.");
        }

        byte[] serializedBytes;
        try {
            if (INT == dataType) {
                serializedBytes = new byte[6]; // 2 for data type + 4 for an int.
                serializeInt(serializedBytes, value);
            } else if (DOUBLE == dataType) {
                serializedBytes = new byte[10]; // 2 for data type + 8 for a double.
                serializeDouble(serializedBytes, value);
            } else if (BOOLEAN == dataType) {
                serializedBytes = new byte[3]; // 2 for data type + 1 for a boolean.
                serializeBoolean(serializedBytes, value);
            } else if (STRING == dataType) {
                serializedBytes = new byte[6 + value.getBytes(StandardCharsets.UTF_8).length];
                serializeString(serializedBytes, value);
            } else {
                throw new IllegalArgumentException("The data type " + dataType + " is null" +
                    " or not supported.");
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("The string value " + value + " can not be parsed" +
                " as " + dataType.name().toUpperCase() + ".");
        }
        serializedBytes[0] = (byte) ((key & 0xF0) >> 8);
        serializedBytes[1] = (byte) (key & 0x0F);
        return serializedBytes;
    }

    /**
     * Deserializes the {@code byte[]} passed into an appropriate object representing the given
     * {@code dataType}.
     *
     * @param dataType The {@code DataType} of the passed value.
     * @param data The byte array containing the value to deserialize.
     * @return The deserialized bytes as an appropriate Object.
     * @throws IllegalArgumentException if the dataType passed is not one of the {@link DataType}
     * enum values.
     */
    public static Object deserialize(DataType dataType, byte[] data, int startIndex, int length) {
        if (INT == dataType) {
            return ((int) data[startIndex + 3]) << 24 | ((int) data[startIndex + 2]) << 16 |
                ((int) data[startIndex + 1]) << 8 | (int) data[startIndex];
        } else if (DOUBLE == dataType) {
            long value = 0;
            for(int i = 0; i < 8; i++) {
                value |= ((long) data[startIndex + i]) << ((7 - i) * 8);
            }
            return Double.longBitsToDouble(value);
        } else if (BOOLEAN == dataType) {
            return data[startIndex] == 1;
        } else if (STRING == dataType) {
            return new String(Arrays.copyOfRange(data, startIndex, startIndex + length),
                StandardCharsets.UTF_8);
        }

        throw new IllegalArgumentException("The data type " + dataType + " is null or not " +
            "supported.");
    }

    private static void serializeInt(byte[] serializedBytes, String value) {
        int integerValue = Integer.parseInt(value);
        serializedBytes[5] = (byte) (integerValue >> 24);
        serializedBytes[4] = (byte) (integerValue >> 16);
        serializedBytes[3] = (byte) (integerValue >> 8);
        serializedBytes[2] = (byte) integerValue;
    }

    private static void serializeDouble(byte[] serializedBytes, String value) {
        long longValue = Double.doubleToLongBits(Double.parseDouble(value));
        for(int i = 0; i < 8; i++) {
            serializedBytes[i + 2] = (byte)((longValue >> ((7 - i) * 8)) & 0xff);
        }
    }

    private static void serializeBoolean(byte[] serializedBytes, String value) {
        if (Boolean.parseBoolean(value)) {
            serializedBytes[2] = 1; // true
        } else {
            serializedBytes[2] = 0; // false
        }
    }

    private static void serializeString(byte[] serializedBytes, String value) {
        byte[] valueAsBytes = value.getBytes(StandardCharsets.UTF_8);
        serializedBytes[2] = (byte) ((valueAsBytes.length & 0xF000) >> 24);
        serializedBytes[3] = (byte) ((valueAsBytes.length & 0x0F00) >> 16);
        serializedBytes[4] = (byte) ((valueAsBytes.length & 0x00F0) >> 8);
        serializedBytes[5] = (byte) (valueAsBytes.length & 0x000F);
        System.arraycopy(valueAsBytes, 0, serializedBytes, 6, valueAsBytes.length);
    }
}