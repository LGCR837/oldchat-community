using System;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Windows.Input;

namespace OldChat.App.ViewModels
{
    public abstract class ObservableObject : INotifyPropertyChanged
    {
        public event PropertyChangedEventHandler PropertyChanged;

        protected void Raise([CallerMemberName] string name = null)
        {
            var handler = PropertyChanged;
            if (handler != null) handler(this, new PropertyChangedEventArgs(name));
        }

        protected bool Set<T>(ref T field, T value, [CallerMemberName] string name = null)
        {
            if (Equals(field, value)) return false;
            field = value;
            Raise(name);
            return true;
        }
    }

    public sealed class RelayCommand : ICommand
    {
        private readonly Action _execute;
        private readonly Func<bool> _canExecute;

        public RelayCommand(Action execute, Func<bool> canExecute = null)
        {
            _execute = execute;
            _canExecute = canExecute;
        }

        public event EventHandler CanExecuteChanged
        {
            add { CommandManager.RequerySuggested += value; }
            remove { CommandManager.RequerySuggested -= value; }
        }

        public bool CanExecute(object parameter)
        {
            return _canExecute == null || _canExecute();
        }

        public void Execute(object parameter)
        {
            _execute();
        }
    }
}
