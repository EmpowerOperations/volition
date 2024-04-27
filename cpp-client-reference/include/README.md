# Volition API Reference

## API Naming Convention

| Name                              | Method type           | Receiver | Arguments    | Returns |
| --------------------------------- | --------------------- | -------- | ------------ | ------- |
| `{object}_create`                 | Constructor           | -        | -            | Object  |
| `{object}_destroy`                | Destructor            | Object   | -            | -       |
| `{object}_get_{value}`            | Accessor              | Object   | -            | Value   |
| `{object}_set_{value}`            | Mutator               | Object   | Value        | -       |
| `{object}_get_{object}`           | Accessor              | Object   | -            | Object  |
| `{object}_set_{object}`           | Instantiating mutator | Object   | -            | Object  |
| `{object}_add_{repeated_values}`  | Mutator               | Object   | Value        | -       |
| `{object}_add_{repeated_objects}` | Instantiating mutator | Object   | -            | Object  |
| `{object}_set_{keyed_values}`     | Mutator               | Object   | Value, value | -       |
| `{object}_set_{keyed_objects}`    | Instantiating mutator | Object   | Value        | Object  |
